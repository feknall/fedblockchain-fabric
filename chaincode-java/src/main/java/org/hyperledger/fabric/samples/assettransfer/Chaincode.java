/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hyperledger.fabric.samples.assettransfer;

import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.contract.ContractInterface;
import org.hyperledger.fabric.contract.annotation.Contact;
import org.hyperledger.fabric.contract.annotation.Contract;
import org.hyperledger.fabric.contract.annotation.Default;
import org.hyperledger.fabric.contract.annotation.Info;
import org.hyperledger.fabric.contract.annotation.License;
import org.hyperledger.fabric.contract.annotation.Transaction;
import org.hyperledger.fabric.shim.ChaincodeException;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.hyperledger.fabric.shim.ledger.CompositeKey;
import org.hyperledger.fabric.shim.ledger.KeyValue;
import org.hyperledger.fabric.shim.ledger.QueryResultsIterator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


@Contract(
        name = "basic",
        info = @Info(
                title = "Federated Learning",
                description = "Privacy-preserving federated learning",
                version = "0.0.1-SNAPSHOT",
                license = @License(
                        name = "Apache 2.0 License",
                        url = "http://www.apache.org/licenses/LICENSE-2.0.html"),
                contact = @Contact(
                        email = "h.fazli.k@gmail.com",
                        name = "Hamid Fazli",
                        url = "https://hyperledger.example.com")))
@Default
public final class Chaincode implements ContractInterface {

    private final Logger logger = Logger.getLogger(getClass().toString());

    private enum ChaincodeErrors {
        MODEL_NOT_FOUND,
        MODEL_METADATA_ALREADY_EXISTS,
        MODEL_METADATA_DOES_NOT_EXISTS,
        TRAINING_NOT_FINISHED,
        ENOUGH_CLIENT_NOT_CHECKED_IN,
        TRAINER_NOT_SELECTED_FOR_ROUND,
        INVALID_ACCESS
    }

    static final String MODEL_METADATA_KEY = "modelMetadataKey";
    static final String MODEL_SECRET_KEY = "modelSecretKey";
    static final String MODEL_SECRET_COUNTER_KEY = "modelSecretCounterKey";
    static final String AGGREGATED_SECRET_COUNTER_KEY = "aggregatedSecretCounterKey";
    static final String END_ROUND_MODEL_KEY = "endRoundModelKey";
    static final String AGGREGATED_SECRET_KEY = "aggregatedSecretKey";
    static final String AGGREGATED_SECRET_COLLECTION = "aggregatedSecretCollection";
    static final String CHECK_IN_TRAINER_KEY = "checkInClientKey";
    static final String CLIENT_SELECTED_FOR_ROUND_KEY = "clientSelectedForRoundKey";

    static final String AGGREGATOR_ATTRIBUTE = "aggregator";
    static final String LEAD_AGGREGATOR_ATTRIBUTE = "leadAggregator";
    static final String TRAINER_ATTRIBUTE = "trainer";
    static final String FL_ADMIN_ATTRIBUTE = "flAdmin";

    static final String ENROLMENT_ID_ATTRIBUTE_KEY = "hf.EnrollmentID";

    static final String ALL_SECRETS_RECEIVED_EVENT = "ALL_SECRETS_RECEIVED_EVENT";
    static final String AGGREGATION_FINISHED_EVENT = "AGGREGATION_FINISHED_EVENT";
    static final String ROUND_FINISHED_EVENT = "ROUND_FINISHED_EVENT";
    static final String ROUND_AND_TRAINING_FINISHED_EVENT = "ROUND_AND_TRAINING_FINISHED_EVENT";
    static final String MODEL_SECRET_ADDED_EVENT = "MODEL_SECRET_ADDED_EVENT";
    static final String START_TRAINING_EVENT = "START_TRAINING_EVENT";
    static final String CREATE_MODEL_METADATA_EVENT = "CREATE_MODEL_METADATA_EVENT";
    static final String AGGREGATED_SECRET_ADDED_EVENT = "AGGREGATED_SECRET_ADDED_EVENT";

    static final String MODEL_METADATA_STATUS_INITIATED = "initiated";
    static final String MODEL_METADATA_STATUS_STARTED = "started";
    static final String MODEL_METADATA_STATUS_FINISHED = "finished";

    static final long CHECK_IN_VALIDATION_PERIOD_MILLIS = 60000;

    // ------------------- START ADMIN ---------------------
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public void initLedger(final Context ctx) {

    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public ModelMetadata startTraining(final Context ctx, final String modelId) {
        checkHasFlAdminRoleOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();
        if (!modelExists(ctx, modelId)) {
            String errorMessage = String.format("ModelMetadata %s doesn't exists", modelId);
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.MODEL_METADATA_DOES_NOT_EXISTS.toString());
        }

        String key = stub.createCompositeKey(MODEL_METADATA_KEY, modelId).toString();
        String json = stub.getStringState(key);
        ModelMetadata oldModelMetadata = ModelMetadata.deserialize(json);
        ModelMetadata newModelMetadata = new ModelMetadata(oldModelMetadata.getModelId(),
                oldModelMetadata.getName(),
                oldModelMetadata.getClientsPerRound(),
                oldModelMetadata.getSecretsPerClient(),
                MODEL_METADATA_STATUS_STARTED,
                oldModelMetadata.getTrainingRounds(),
                oldModelMetadata.getCurrentRound());
        String newJson = newModelMetadata.serialize();
        stub.putStringState(key, newJson);

        selectTrainersForRound(ctx, newModelMetadata);

        stub.setEvent(START_TRAINING_EVENT, newJson.getBytes());

        return newModelMetadata;
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public ModelMetadata createModelMetadata(final Context ctx, final String modelId,
                                             final String modelName, final String clientsPerRound,
                                             final String secretsPerClient, final String trainingRounds) {
        checkHasFlAdminRoleOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();

        if (modelExists(ctx, modelId)) {
            String errorMessage = String.format("ModelMetadata %s already exists", modelId);
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.MODEL_METADATA_ALREADY_EXISTS.toString());
        }

        ModelMetadata model = new ModelMetadata(modelId,
                modelName,
                Integer.parseInt(clientsPerRound),
                Integer.parseInt(secretsPerClient),
                MODEL_METADATA_STATUS_INITIATED,
                Integer.parseInt(trainingRounds),
                0);

        String json = model.serialize();
        String key = stub.createCompositeKey(MODEL_METADATA_KEY, modelId).toString();
        stub.putStringState(key, json);

        stub.setEvent(CREATE_MODEL_METADATA_EVENT, json.getBytes());

        return model;
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public ModelMetadata getModelMetadata(final Context ctx, final String modelId) {
        ChaincodeStub stub = ctx.getStub();
        String modelMetadataJson = toJsonIfModelMetadataExistsOrThrow(stub, modelId);

        return ModelMetadata.deserialize(modelMetadataJson);
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public CheckInList getCheckInInfo(final Context ctx) {
        checkHasFlAdminRoleOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();

        List<TrainerMetadata> trainerMetadataList = new ArrayList<>();
        String key = stub.createCompositeKey(CHECK_IN_TRAINER_KEY).toString();
        QueryResultsIterator<KeyValue> iterator = stub.getStateByPartialCompositeKey(key);
        for (KeyValue result : iterator) {
            String value = result.getStringValue();
            if (notNullAndEmpty(value)) {
                TrainerMetadata trainerMetadata = TrainerMetadata.deserialize(value);
                trainerMetadataList.add(trainerMetadata);
            }
        }

        return new CheckInList(trainerMetadataList);
    }

    private void selectTrainersForRound(final Context ctx, final ModelMetadata modelMetadata) {
        ChaincodeStub stub = ctx.getStub();
        List<TrainerMetadata> checkedInClients = new ArrayList<>();
        String checkInKey = stub.createCompositeKey(CHECK_IN_TRAINER_KEY).toString();
        QueryResultsIterator<KeyValue> iterator = stub.getStateByPartialCompositeKey(checkInKey);
        long currentTime = System.currentTimeMillis();
        for (KeyValue result : iterator) {
            String value = result.getStringValue();
            if (notNullAndEmpty(value)) {
                TrainerMetadata trainerMetadata = TrainerMetadata.deserialize(value);
                long period = currentTime - Long.parseLong(trainerMetadata.getCheckedInTimestamp());
                if (period < CHECK_IN_VALIDATION_PERIOD_MILLIS) {
                    checkedInClients.add(trainerMetadata);
                } else {
                    logger.log(Level.INFO, String.format("Trainer %s is not active.", trainerMetadata.getCheckedInTimestamp()));
                    stub.delState(stub.createCompositeKey(CHECK_IN_TRAINER_KEY, trainerMetadata.getClientId()).toString());
                    logger.log(Level.INFO, String.format("Trainer %s deleted from checked-in trainers.", trainerMetadata.getCheckedInTimestamp()));
                }
            }
        }

        int numberOfCheckedInClients = checkedInClients.size();
        int numberOfRequiredClients = modelMetadata.getClientsPerRound();

        if (numberOfRequiredClients > numberOfCheckedInClients) {
            String errorMessage = String.format("More clients are required for model %s. "
                            + "Number of checked-in clients: %s, "
                            + "Number of required clients: %s", modelMetadata.getModelId(),
                    numberOfCheckedInClients,
                    numberOfRequiredClients);
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.ENOUGH_CLIENT_NOT_CHECKED_IN.toString());
        }

        Collections.shuffle(checkedInClients);

        stub.delState(stub.createCompositeKey(CLIENT_SELECTED_FOR_ROUND_KEY).toString());

        for (int i = 0; i < numberOfRequiredClients; i++) {
            TrainerMetadata old = checkedInClients.get(i);
            TrainerMetadata trainerMetadataNew = new TrainerMetadata(old.getClientId(),
                    old.getUsername(),
                    old.getCheckedInTimestamp(),
                    modelMetadata.getCurrentRound());

            String selectedClientKey = stub
                    .createCompositeKey(CLIENT_SELECTED_FOR_ROUND_KEY, trainerMetadataNew.getUsername())
                    .toString();
            String trainerMetadataStr = trainerMetadataNew.serialize();
            stub.putStringState(selectedClientKey, trainerMetadataStr);
        }
    }

    private static boolean notNullAndEmpty(final String str) {
        return str != null && !str.isEmpty();
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public EndRoundModel getTrainedModel(final Context ctx, final String modelId) {
        ChaincodeStub stub = ctx.getStub();
        String modelMetadataKey = stub.createCompositeKey(MODEL_METADATA_KEY, modelId).toString();
        String modelMetadataJson = stub.getStringState(modelMetadataKey);
        ModelMetadata metadata = ModelMetadata.deserialize(modelMetadataJson);
        if (!metadata.getStatus().equals(MODEL_METADATA_STATUS_FINISHED)) {
            String errorMessage = String.format("Training of ModelMetadata %s is not finished yet", modelId);
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.TRAINING_NOT_FINISHED.toString());
        }

        String key = stub.createCompositeKey(END_ROUND_MODEL_KEY,
                        modelId,
                        String.valueOf(metadata.getTrainingRounds()))
                .toString();
        String endRoundJson = stub.getStringState(key);
        return EndRoundModel.deserialize(endRoundJson);
    }

    // ------------------- FINISH ADMIN ---------------------

    // ------------------- START TRAINER ---------------------
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public ModelMetadata addModelSecret(final Context ctx, final String modelId, final String weights) {
        checkHasTrainerRoleOrThrow(ctx);
        checkTrainerIsSelectedForRoundOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();

        String modelMetadataJson = toJsonIfModelMetadataExistsOrThrow(stub, modelId);
        ModelMetadata modelMetadata = ModelMetadata.deserialize(modelMetadataJson);

        String round = String.valueOf(modelMetadata.getCurrentRound());

        ModelSecret modelSecret = new ModelSecret(modelId, round, weights);
        String modelSecretJson = modelSecret.serialize();

        String collectionName = getCollectionName(ctx);
        String username = getClientUsername(ctx);
        String secretKey = stub.createCompositeKey(MODEL_SECRET_KEY, modelId, round, username).toString();

        stub.putPrivateData(collectionName, secretKey, modelSecretJson);

        String counterKey = stub.createCompositeKey(MODEL_SECRET_COUNTER_KEY, modelId, round).toString();
        String oldCounterValue = new String(stub.getPrivateData(collectionName, counterKey));
        logger.log(Level.INFO, "oldCounterValue: " + oldCounterValue);

        int newValue = 1;
        if (!oldCounterValue.isEmpty()) {
            newValue = Integer.parseInt(oldCounterValue) + 1;
        }
        stub.putPrivateData(collectionName, counterKey, String.valueOf(newValue));
        logger.log(Level.INFO, "CounterKey: " +  counterKey);
        byte[] event = new ModelSecret(modelSecret.getModelId(), String.valueOf(newValue), null)
                .serialize()
                .getBytes();

        stub.setEvent(MODEL_SECRET_ADDED_EVENT, event);

        if (newValue == modelMetadata.getClientsPerRound()) {
            logger.log(Level.INFO, "Enough secrets are received!");
            stub.setEvent(ALL_SECRETS_RECEIVED_EVENT, event);
        } else {
            logger.log(Level.INFO, "Looking for more secrets. Current value: " + newValue);
        }

        logger.log(Level.INFO, "ModelUpdate " + secretKey + " stored successfully in " + collectionName);

        return modelMetadata;
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public EndRoundModel readEndRoundModel(final Context ctx, final String modelId, final String round) throws Exception {
        ChaincodeStub stub = ctx.getStub();

        CompositeKey key = stub.createCompositeKey(END_ROUND_MODEL_KEY, modelId, round);

        String endRoundModelJson = stub.getStringState(key.toString());
        if (endRoundModelJson == null || endRoundModelJson.length() == 0) {
            String errorMessage = String.format("EndRoundModel not found. modelId: %s, round: %s", modelId, round);
            logger.log(Level.SEVERE, errorMessage, endRoundModelJson);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.INVALID_ACCESS.toString());
        }

        EndRoundModel endRoundModelSecret = EndRoundModel.deserialize(endRoundModelJson);
        logger.log(Level.INFO, "EndRoundModel for round %s of model %s");

        return endRoundModelSecret;
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public TrainerMetadata checkInTrainer(final Context ctx) {
        String username = getClientUsername(ctx);

        ChaincodeStub stub = ctx.getStub();
        String key = stub.createCompositeKey(CHECK_IN_TRAINER_KEY, username).toString();

        String timestamp = String.valueOf(System.currentTimeMillis());
        TrainerMetadata trainerMetadata = new TrainerMetadata(ctx.getClientIdentity().getId(), username, timestamp);
        stub.putStringState(key, trainerMetadata.serialize());

        return trainerMetadata;
    }

    private void checkHasTrainerRoleOrThrow(final Context ctx) {
        if (checkHasTrainerAttribute(ctx)) {
            return;
        }
        String errorMessage = "User " + ctx.getClientIdentity().getId() + " has no trainer attribute";
        logger.log(Level.SEVERE, errorMessage);
        throw new ChaincodeException(errorMessage, ChaincodeErrors.INVALID_ACCESS.toString());
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public Boolean checkHasTrainerAttribute(final Context ctx) {
        return ctx.getClientIdentity().assertAttributeValue(TRAINER_ATTRIBUTE, Boolean.TRUE.toString());
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public Boolean checkIAmSelectedForRound(final Context ctx) {
        ChaincodeStub stub = ctx.getStub();

        String selectedClientKey = stub
                .createCompositeKey(CLIENT_SELECTED_FOR_ROUND_KEY, getClientUsername(ctx))
                .toString();
        String value = stub.getStringState(selectedClientKey);
        if (notNullAndEmpty(value)) {
            return Boolean.TRUE;
        } else {
            return Boolean.FALSE;
        }
    }
    // ------------------- FINISH TRAINER ---------------------

    private void checkTrainerIsSelectedForRoundOrThrow(final Context ctx) {
        String username = getClientUsername(ctx);

        ChaincodeStub stub = ctx.getStub();
        String selectedClientKey = stub.createCompositeKey(CLIENT_SELECTED_FOR_ROUND_KEY, username).toString();
        String value = stub.getStringState(selectedClientKey);

        if (notNullAndEmpty(value)) {
            logger.log(Level.INFO, String.format("Trainer %s is selected for this round", username));
        } else {
            String errorMessage = String.format("Trainer %s is NOT selected for this round", username);
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.TRAINER_NOT_SELECTED_FOR_ROUND.toString());
        }
    }

    // ------------------- START LEAD AGGREGATOR ---------------------
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public ModelMetadata addEndRoundModel(final Context ctx, final String modelId, final String round, final String weights) {
        checkHasLeadAggregatorRoleOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();

        String modelMetadataJson = toJsonIfModelMetadataExistsOrThrow(stub, modelId);
        ModelMetadata modelMetadata = ModelMetadata.deserialize(modelMetadataJson);
        boolean finishTraining = Integer.parseInt(round) >= modelMetadata.getTrainingRounds();
        if (finishTraining) {
            String key = stub.createCompositeKey(MODEL_METADATA_KEY, modelId).toString();
            ModelMetadata newModelMetadata = new ModelMetadata(modelMetadata.getModelId(),
                    modelMetadata.getName(),
                    modelMetadata.getClientsPerRound(),
                    modelMetadata.getSecretsPerClient(),
                    MODEL_METADATA_STATUS_STARTED,
                    modelMetadata.getTrainingRounds(),
                    Integer.parseInt(round) + 1);
            stub.putStringState(key, newModelMetadata.serialize());
        }

        EndRoundModel endRoundModel = new EndRoundModel(modelId, round, weights);
        String modelUpdateJson = endRoundModel.serialize();

        String key = stub.createCompositeKey(END_ROUND_MODEL_KEY, modelId, round).toString();
        stub.putStringState(key, modelUpdateJson);

        if (finishTraining) {
            stub.setEvent(ROUND_AND_TRAINING_FINISHED_EVENT, modelMetadataJson.getBytes());
        } else {
            stub.setEvent(ROUND_FINISHED_EVENT, modelMetadataJson.getBytes());

            selectTrainersForRound(ctx, modelMetadata);
        }

        logger.log(Level.INFO, "ModelUpdate " + key + " stored successfully in public :)");
        logger.log(Level.INFO, "ModelUpdate JSON: " + modelUpdateJson);
        return modelMetadata;
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public AggregatedSecret[] readAggregatedSecret(final Context ctx, final String modelId, final String round) throws Exception {
        checkHasLeadAggregatorRoleOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();
        CompositeKey key = stub.createCompositeKey(AGGREGATED_SECRET_KEY, modelId, round);

        String collectionName = getCollectionName(ctx);

        List<AggregatedSecret> aggregatedSecretList = new ArrayList<>();
        try (QueryResultsIterator<KeyValue> results = stub.getPrivateDataByPartialCompositeKey(collectionName, key)) {
            for (KeyValue result : results) {
                if (result.getStringValue() == null || result.getStringValue().length() == 0) {
                    logger.log(Level.SEVERE, "Invalid AggregatedModelUpdate json: %s\n", result.getStringValue());
                    continue;
                }
                AggregatedSecret aggregatedSecret = AggregatedSecret.deserialize(result.getStringValue());
                aggregatedSecretList.add(aggregatedSecret);
                logger.log(Level.INFO, String.format("Round %s of Model %s read successfully",
                        aggregatedSecret.getRound(), aggregatedSecret.getModelId()));
            }
        }

        return aggregatedSecretList.toArray(new AggregatedSecret[0]);
    }

    private void checkHasLeadAggregatorRoleOrThrow(final Context ctx) {
        if (checkHasLeadAggregatorAttribute(ctx)) {
            return;
        }
        String errorMessage = "User " + ctx.getClientIdentity().getId() + " has no leadAggregator attribute";
        logger.log(Level.SEVERE, errorMessage);
        throw new ChaincodeException(errorMessage, ChaincodeErrors.INVALID_ACCESS.toString());
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public Boolean checkHasLeadAggregatorAttribute(final Context ctx) {
        return ctx.getClientIdentity().assertAttributeValue(LEAD_AGGREGATOR_ATTRIBUTE, Boolean.TRUE.toString());
    }
    // ------------------- FINISH LEAD AGGREGATOR ---------------------

    // ------------------- START AGGREGATOR ---------------------
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public ModelMetadata addAggregatedSecret(final Context ctx, final String modelId, final String round, final String weights) {
        checkHasAggregatorRoleOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();

        String modelMetadataJson = toJsonIfModelMetadataExistsOrThrow(stub, modelId);
        ModelMetadata modelMetadata = ModelMetadata.deserialize(modelMetadataJson);

        AggregatedSecret aggregatedSecret = new AggregatedSecret(modelId, round, weights);
        String modelUpdateJson = aggregatedSecret.serialize();

        String username = getClientUsername(ctx);
        String key = stub.createCompositeKey(AGGREGATED_SECRET_KEY, modelId, round, username).toString();
        stub.putPrivateData(AGGREGATED_SECRET_COLLECTION, key, modelUpdateJson);

        String counterKey = stub.createCompositeKey(AGGREGATED_SECRET_COUNTER_KEY, modelId, round).toString();
        String oldCounterValue = stub.getStringState(counterKey);

        int newValue = 1;
        if (!oldCounterValue.isEmpty()) {
            newValue = Integer.parseInt(oldCounterValue) + 1;
        }

        stub.putStringState(counterKey, String.valueOf(newValue));

        byte[] event = new AggregatedSecret(aggregatedSecret.getModelId(), aggregatedSecret.getRound(), null)
                .serialize()
                .getBytes();
        stub.setEvent(AGGREGATED_SECRET_ADDED_EVENT, event);

        if (newValue == modelMetadata.getSecretsPerClient()) {
            stub.setEvent(AGGREGATION_FINISHED_EVENT, event);
        }

        logger.log(Level.INFO, "AggregatedModelUpdate " + key + " stored successfully in " + AGGREGATED_SECRET_COLLECTION);
        logger.log(Level.INFO, "ModelUpdate JSON: " + modelUpdateJson);
        return modelMetadata;
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public ModelSecretList readModelSecretsForCurrentRound(final Context ctx, final String modelId) throws Exception {
        ChaincodeStub stub = ctx.getStub();

        String modelMetadataJson = toJsonIfModelMetadataExistsOrThrow(stub, modelId);
        ModelMetadata modelMetadata = ModelMetadata.deserialize(modelMetadataJson);

        return readModelSecrets(ctx, modelId, String.valueOf(modelMetadata.getCurrentRound()));
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public ModelSecretList readModelSecrets(final Context ctx, final String modelId, final String round) throws Exception {
        checkHasAggregatorRoleOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();
        String collectionName = getCollectionName(ctx);
        CompositeKey key = stub.createCompositeKey(MODEL_SECRET_KEY, modelId, round);
        List<ModelSecret> modelSecretList = new ArrayList<>();
        try (QueryResultsIterator<KeyValue> results = stub.getPrivateDataByPartialCompositeKey(collectionName, key)) {
            for (KeyValue result : results) {
                if (result.getStringValue() == null || result.getStringValue().length() == 0) {
                    logger.log(Level.SEVERE, "Invalid ModelUpdate json: %s\n", result.getStringValue());
                    continue;
                }
                ModelSecret modelSecret = ModelSecret.deserialize(result.getStringValue());
                modelSecretList.add(modelSecret);
                logger.log(Level.INFO, String.format("Round %s of Model %s read successfully", modelSecret.getRound(), modelSecret.getModelId()));
            }
        }

        return new ModelSecretList(modelSecretList);
    }

    private void checkHasAggregatorRoleOrThrow(final Context ctx) {
        if (checkHasAggregatorAttribute(ctx)) {
            return;
        }
        String errorMessage = "User " + ctx.getClientIdentity().getId() + "has no aggregator attribute";
        logger.log(Level.SEVERE, errorMessage);
        throw new ChaincodeException(errorMessage, ChaincodeErrors.INVALID_ACCESS.toString());
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public Boolean checkHasAggregatorAttribute(final Context ctx) {
        return ctx.getClientIdentity().assertAttributeValue(AGGREGATOR_ATTRIBUTE, Boolean.TRUE.toString());
    }
    // ------------------- FINISH AGGREGATOR ---------------------

    private String toJsonIfModelMetadataExistsOrThrow(final ChaincodeStub stub, final String modelId) {
        String key = stub.createCompositeKey(MODEL_METADATA_KEY, modelId).toString();
        String modelJson = stub.getStringState(key);
        if (modelJson == null || modelJson.isEmpty()) {
            String errorMessage = String.format("ModelMetadata %s does not exist", modelId);
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.MODEL_NOT_FOUND.toString());
        }
        logger.log(Level.INFO, "ModelMetadata Json: " + modelJson);
        return modelJson;
    }

    private void checkHasFlAdminRoleOrThrow(final Context ctx) {
        if (checkHasFlAdminAttribute(ctx)) {
            return;
        }
        String errorMessage = "User " + ctx.getClientIdentity().getId() + "has no admin attribute";
        logger.log(Level.SEVERE, errorMessage);
        throw new ChaincodeException(errorMessage, ChaincodeErrors.INVALID_ACCESS.toString());
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public Boolean checkHasFlAdminAttribute(final Context ctx) {
        return ctx.getClientIdentity().assertAttributeValue(FL_ADMIN_ATTRIBUTE, Boolean.TRUE.toString());
    }

    // ------------------- START GENERAL ---------------------

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public CheckInList getSelectedTrainersForCurrentRound(final Context ctx) {
        ChaincodeStub stub = ctx.getStub();

        String selectedClientKey = stub.createCompositeKey(CLIENT_SELECTED_FOR_ROUND_KEY).toString();
        QueryResultsIterator<KeyValue> iterator = stub.getStateByPartialCompositeKey(selectedClientKey);

        List<TrainerMetadata> trainerMetadataList = new ArrayList<>();
        for (KeyValue result : iterator) {
            String value = result.getStringValue();
            if (notNullAndEmpty(value)) {
                TrainerMetadata trainerMetadata = TrainerMetadata.deserialize(value);
                trainerMetadataList.add(trainerMetadata);
            }
        }

        return new CheckInList(trainerMetadataList);
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public PersonalInfo getPersonalInfo(final Context ctx) {
        ChaincodeStub stub = ctx.getStub();

        String username = getClientUsername(ctx);
        String checkInKey = stub.createCompositeKey(CHECK_IN_TRAINER_KEY, username).toString();

        Boolean checkedIn = notNullAndEmpty(stub.getStringState(checkInKey)) ? Boolean.TRUE : Boolean.FALSE;
        String mspId = ctx.getClientIdentity().getMSPID();
        String role = getRole(ctx);

        String clientId = ctx.getClientIdentity().getId();
        if (role.equals(TRAINER_ATTRIBUTE)) {
            String key = stub.createCompositeKey(CLIENT_SELECTED_FOR_ROUND_KEY, username).toString();
            String value = stub.getStringState(key);
            Boolean selectedForRound = notNullAndEmpty(value) ? Boolean.TRUE : Boolean.FALSE;
            return new PersonalInfo(clientId, role, mspId, username, selectedForRound, checkedIn);
        }

        return new PersonalInfo(clientId, role, mspId, username, null, checkedIn);
    }

    private String getRole(final Context ctx) {
        if (checkHasAggregatorAttribute(ctx)) {
            return AGGREGATOR_ATTRIBUTE;
        } else if (checkHasTrainerAttribute(ctx)) {
            return TRAINER_ATTRIBUTE;
        } else if (checkHasLeadAggregatorAttribute(ctx)) {
            return LEAD_AGGREGATOR_ATTRIBUTE;
        }
        return "unknown";
    }
    // ------------------- FINISH GENERAL ---------------------


    private String getClientUsername(final Context ctx) {
        return ctx.getClientIdentity().getAttributeValue(ENROLMENT_ID_ATTRIBUTE_KEY);
    }

    private boolean modelExists(final Context ctx, final String modelId) {
        ChaincodeStub stub = ctx.getStub();
        String key = stub.createCompositeKey(MODEL_METADATA_KEY, modelId).toString();
        String modelJson = stub.getStringState(key);

        return (modelJson != null && !modelJson.isEmpty());
    }

    private void verifyClientOrgMatchesPeerOrg(final Context ctx) {
        String clientMSPID = ctx.getClientIdentity().getMSPID();
        String peerMSPID = ctx.getStub().getMspId();

        if (!peerMSPID.equals(clientMSPID)) {
            String errorMessage = String.format("Client from org %s is not authorized to read or write private data from an org %s peer", clientMSPID, peerMSPID);
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.INVALID_ACCESS.toString());
        }
    }

    private String getCollectionName(final Context ctx) {
        // Get the MSP ID of submitting client identity
        String clientMSPID = ctx.getClientIdentity().getMSPID();
        // Create the collection name
        return clientMSPID + "PrivateCollection";
    }

}
