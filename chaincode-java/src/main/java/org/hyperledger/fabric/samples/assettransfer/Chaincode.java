/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hyperledger.fabric.samples.assettransfer;

import org.hyperledger.fabric.contract.ClientIdentity;
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
//import java.util.UUID;

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

    static final String ALL_SECRETS_RECEIVED_EVENT = "ALL_SECRETS_RECEIVED_EVENT";
    static final String AGGREGATION_FINISHED_EVENT = "AGGREGATION_FINISHED_EVENT";
    static final String ROUND_FINISHED_EVENT = "ROUND_FINISHED_EVENT";
    static final String ROUND_AND_TRAINING_FINISHED_EVENT = "ROUND_AND_TRAINING_FINISHED_EVENT";
    static final String MODEL_SECRET_ADDED_EVENT = "MODEL_SECRET_ADDED_EVENT";
    static final String START_TRAINING_EVENT = "START_TRAINING_EVENT";
    static final String CREATE_MODEL_METADATA_EVENT = "CREATE_MODEL_METADATA_EVENT";
    static final String AGGREGATED_SECRET_ADDED_EVENT = "AGGREGATED_SECRET_ADDED_EVENT";
    static final String CLIENT_SELECTED_FOR_ROUND_EVENT = "CLIENT_SELECTED_FOR_ROUND_EVENT";

    static final String MODEL_METADATA_STATUS_INITIATED = "initiated";
    static final String MODEL_METADATA_STATUS_STARTED = "started";
    static final String MODEL_METADATA_STATUS_FINISHED = "finished";

    static final long CHECK_IN_VALIDATION_PERIOD_MILLIS = 60000;

    // ------------------- START ADMIN ---------------------
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public void InitLedger(final Context ctx) {

    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public ModelMetadata StartTraining(final Context ctx, final String modelId) {
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
                oldModelMetadata.getTrainingRounds());
        String newJson = newModelMetadata.serialize();
        stub.putStringState(key, newJson);

        stub.setEvent(START_TRAINING_EVENT, newJson.getBytes());

        selectTrainersForRound(ctx, newModelMetadata);

        return newModelMetadata;
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public ModelMetadata CreateModelMetadata(final Context ctx, final String modelId,
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
                clientsPerRound,
                secretsPerClient,
                MODEL_METADATA_STATUS_INITIATED,
                trainingRounds);

        String json = model.serialize();
        String key = stub.createCompositeKey(MODEL_METADATA_KEY, modelId).toString();
        stub.putStringState(key, json);

        stub.setEvent(CREATE_MODEL_METADATA_EVENT, json.getBytes());

        return model;
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public CheckInInfo GetCheckInInfo(final Context ctx) {
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

        return new CheckInInfo(trainerMetadataList);
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public CheckInInfo GetSelectedTrainersForCurrentRound(final Context ctx) {
        checkHasFlAdminRoleOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();

        String selectedClientKey = stub.createCompositeKey(CLIENT_SELECTED_FOR_ROUND_KEY).toString();
        String collectionName = getCollectionName(ctx);
        QueryResultsIterator<KeyValue> iterator = stub.getPrivateDataByPartialCompositeKey(collectionName, selectedClientKey);

        List<TrainerMetadata> trainerMetadataList = new ArrayList<>();
        for (KeyValue result : iterator) {
            String value = result.getStringValue();
            if (notNullAndEmpty(value)) {
                TrainerMetadata trainerMetadata = TrainerMetadata.deserialize(value);
                trainerMetadataList.add(trainerMetadata);
            }
        }

        return new CheckInInfo(trainerMetadataList);
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
        int numberOfRequiredClients = Integer.parseInt(modelMetadata.getClientsPerRound());

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

        String collectionName = getCollectionName(ctx);

        stub.delPrivateData(collectionName, stub.createCompositeKey(CLIENT_SELECTED_FOR_ROUND_KEY).toString());

        for (int i = 0; i < numberOfRequiredClients; i++) {
            TrainerMetadata trainerMetadata = checkedInClients.get(i);
            String selectedClientKey = stub.createCompositeKey(CLIENT_SELECTED_FOR_ROUND_KEY, trainerMetadata.getClientId()).toString();
            String trainerMetadataStr = trainerMetadata.serialize();
            stub.putPrivateData(collectionName, selectedClientKey, trainerMetadataStr);

            stub.setEvent(CLIENT_SELECTED_FOR_ROUND_EVENT, trainerMetadataStr.getBytes());
        }
    }

    private static boolean notNullAndEmpty(final String str) {
        return str != null && !str.isEmpty();
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public TrainerMetadata CheckInTrainer(final Context ctx) {
        String clientId = ctx.getClientIdentity().getId();

        ChaincodeStub stub = ctx.getStub();
        String key = stub.createCompositeKey(CHECK_IN_TRAINER_KEY, clientId).toString();

        String timestamp = String.valueOf(System.currentTimeMillis());
        TrainerMetadata trainerMetadata = new TrainerMetadata(clientId, timestamp);
        stub.putStringState(key, trainerMetadata.serialize());

        return trainerMetadata;
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public EndRoundModel GetTrainedModel(final Context ctx, final String modelId) {
        ChaincodeStub stub = ctx.getStub();
        String modelMetadataKey = stub.createCompositeKey(MODEL_METADATA_KEY, modelId).toString();
        String modelMetadataJson = stub.getStringState(modelMetadataKey);
        ModelMetadata metadata = ModelMetadata.deserialize(modelMetadataJson);
        if (!metadata.getStatus().equals(MODEL_METADATA_STATUS_FINISHED)) {
            String errorMessage = String.format("Training of ModelMetadata %s is not finished yet", modelId);
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.TRAINING_NOT_FINISHED.toString());
        }

        String key = stub.createCompositeKey(END_ROUND_MODEL_KEY, modelId, metadata.getTrainingRounds()).toString();
        String endRoundJson = stub.getStringState(key);
        return EndRoundModel.deserialize(endRoundJson);
    }

    // ------------------- FINISH ADMIN ---------------------

    // ------------------- START TRAINER ---------------------
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public ModelMetadata AddModelSecret(final Context ctx, final String modelId, final String round, final String weights) {
        checkHasTrainerRoleOrThrow(ctx);
        checkTrainerIsSelectedForRoundOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();

        String modelMetadataJson = toJsonIfModelMetadataExistsOrThrow(stub, modelId);
        ModelMetadata modelMetadata = ModelMetadata.deserialize(modelMetadataJson);

        ModelSecret modelSecret = new ModelSecret(modelId, round, weights);
        String modelSecretJson = modelSecret.serialize();

        String collectionName = getCollectionName(ctx);
        String secretKey = stub.createCompositeKey(MODEL_SECRET_KEY, modelId, round, ctx.getClientIdentity().getId()).toString();

        stub.putPrivateData(collectionName, secretKey, modelSecretJson);

        String counterKey = stub.createCompositeKey(MODEL_SECRET_COUNTER_KEY, modelId, round).toString();
        String oldCounterValue = new String(stub.getPrivateData(collectionName, counterKey));

        int newValue = 1;
        if (!oldCounterValue.isEmpty()) {
            newValue = Integer.parseInt(oldCounterValue) + 1;
        }

        stub.putPrivateData(collectionName, counterKey, String.valueOf(newValue));

        byte[] event = new ModelSecret(modelSecret.getModelId(), String.valueOf(newValue), null)
                .serialize()
                .getBytes();
        stub.setEvent(MODEL_SECRET_ADDED_EVENT, event);

        if (newValue == Integer.parseInt(modelMetadata.getClientsPerRound())) {
            stub.setEvent(ALL_SECRETS_RECEIVED_EVENT, event);
        }

        logger.log(Level.INFO, "ModelUpdate " + secretKey + " stored successfully in " + collectionName);
        logger.log(Level.INFO, "ModelUpdate JSON: " + modelSecretJson);

        return modelMetadata;
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public EndRoundModel ReadEndRoundModel(final Context ctx, final String modelId, final String round) throws Exception {
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

    private void checkHasTrainerRoleOrThrow(final Context ctx) {
        if (ctx.getClientIdentity().assertAttributeValue(TRAINER_ATTRIBUTE, Boolean.FALSE.toString())) {
            String errorMessage = "User " + ctx.getClientIdentity().getId() + "has no trainer attribute";
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.INVALID_ACCESS.toString());
        }
    }
    // ------------------- FINISH TRAINER ---------------------

    private void checkTrainerIsSelectedForRoundOrThrow(final Context ctx) {
        String trainerId = ctx.getClientIdentity().getId();

        ChaincodeStub stub = ctx.getStub();
        String selectedClientKey = stub.createCompositeKey(CLIENT_SELECTED_FOR_ROUND_KEY, trainerId).toString();
        String collectionName = getCollectionName(ctx);
        String value = new String(stub.getPrivateData(collectionName, selectedClientKey));

        if (notNullAndEmpty(value)) {
            logger.log(Level.INFO, String.format("Trainer %s is selected for this round", trainerId));
            stub.delPrivateData(collectionName, selectedClientKey);
        } else {
            String errorMessage = String.format("Trainer %s is NOT selected for this round", trainerId);
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.TRAINER_NOT_SELECTED_FOR_ROUND.toString());
        }
    }

    // ------------------- START LEAD AGGREGATOR ---------------------
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public ModelMetadata AddEndRoundModel(final Context ctx, final String modelId, final String round, final String weights) {
        checkHasLeadAggregatorRoleOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();

        String modelMetadataJson = toJsonIfModelMetadataExistsOrThrow(stub, modelId);
        ModelMetadata modelMetadata = ModelMetadata.deserialize(modelMetadataJson);
        boolean finishTraining = Integer.parseInt(round) >= Integer.parseInt(modelMetadata.getTrainingRounds());
        if (finishTraining) {
            String key = stub.createCompositeKey(MODEL_METADATA_KEY, modelId).toString();
            ModelMetadata newModelMetadata = new ModelMetadata(modelMetadata.getModelId(),
                    modelMetadata.getName(),
                    modelMetadata.getClientsPerRound(),
                    modelMetadata.getSecretsPerClient(),
                    MODEL_METADATA_STATUS_STARTED,
                    modelMetadata.getTrainingRounds());
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
    public AggregatedSecret[] ReadAggregatedSecret(final Context ctx, final String modelId, final String round) throws Exception {
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
        if (ctx.getClientIdentity().assertAttributeValue(LEAD_AGGREGATOR_ATTRIBUTE, Boolean.FALSE.toString())) {
            String errorMessage = "User " + ctx.getClientIdentity().getId() + "has no leadAggregator attribute";
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.INVALID_ACCESS.toString());
        }
    }
    // ------------------- FINISH LEAD AGGREGATOR ---------------------

    // ------------------- START AGGREGATOR ---------------------
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public ModelMetadata AddAggregatedSecret(final Context ctx, final String modelId, final String round, final String weights) {
        checkHasAggregatorRoleOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();

        String modelMetadataJson = toJsonIfModelMetadataExistsOrThrow(stub, modelId);
        ModelMetadata modelMetadata = ModelMetadata.deserialize(modelMetadataJson);

        AggregatedSecret aggregatedSecret = new AggregatedSecret(modelId, round, weights);
        String modelUpdateJson = aggregatedSecret.serialize();

        String key = stub.createCompositeKey(AGGREGATED_SECRET_KEY, modelId, round, ctx.getClientIdentity().getId()).toString();
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

        if (newValue == Integer.parseInt(modelMetadata.getSecretsPerClient())) {
            stub.setEvent(AGGREGATION_FINISHED_EVENT, event);
        }

        logger.log(Level.INFO, "AggregatedModelUpdate " + key + " stored successfully in " + AGGREGATED_SECRET_COLLECTION);
        logger.log(Level.INFO, "ModelUpdate JSON: " + modelUpdateJson);
        return modelMetadata;
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public ModelSecret[] ReadModelSecrets(final Context ctx, final String modelId, final String round) throws Exception {
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

        return modelSecretList.toArray(new ModelSecret[0]);
    }

    private void checkHasAggregatorRoleOrThrow(final Context ctx) {
        if (ctx.getClientIdentity().assertAttributeValue(AGGREGATOR_ATTRIBUTE, Boolean.FALSE.toString())) {
            String errorMessage = "User " + ctx.getClientIdentity().getId() + "has no aggregator attribute";
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.INVALID_ACCESS.toString());
        }
    }
    // ------------------- FINISH AGGREGATOR ---------------------

    private String toJsonIfModelMetadataExistsOrThrow(final ChaincodeStub stub, final String modelId) {
        String modelJson = stub.getStringState(modelId);
        if (modelJson == null || modelJson.isEmpty()) {
            String errorMessage = String.format("ModelMetadata %s does not exist", modelId);
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.MODEL_NOT_FOUND.toString());
        }
        logger.log(Level.INFO, "ModelMetadata Json: " + modelJson);
        return modelJson;
    }

    private void checkHasFlAdminRoleOrThrow(final Context ctx) {
        if (ctx.getClientIdentity().assertAttributeValue(FL_ADMIN_ATTRIBUTE, Boolean.FALSE.toString())) {
            String errorMessage = "User " + ctx.getClientIdentity().getId() + "has no admin attribute";
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.INVALID_ACCESS.toString());
        }
    }

    // ------------------- START GENERAL ---------------------
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public PersonalInfo GetPersonalInfo(final Context ctx) {
        ChaincodeStub stub = ctx.getStub();

        String clientId = ctx.getClientIdentity().getId();
        String mspId = ctx.getClientIdentity().getMSPID();
        String role = getRole(ctx.getClientIdentity());

        if (role.equals(TRAINER_ATTRIBUTE)) {
            String key = stub.createCompositeKey(CLIENT_SELECTED_FOR_ROUND_KEY, ctx.getClientIdentity().getId()).toString();
            String collectionName = getCollectionName(ctx);
            String value = new String(stub.getPrivateData(collectionName, key));
            Boolean selectedForRound = notNullAndEmpty(value) ? Boolean.TRUE : Boolean.FALSE;
            return new PersonalInfo(clientId, role, selectedForRound, mspId);
        }

        return new PersonalInfo(clientId, role, null, mspId);
    }

    private String getRole(final ClientIdentity identity) {
        if (identity.assertAttributeValue(AGGREGATOR_ATTRIBUTE, Boolean.TRUE.toString())) {
            return AGGREGATOR_ATTRIBUTE;
        } else if (identity.assertAttributeValue(TRAINER_ATTRIBUTE, Boolean.TRUE.toString())) {
            return TRAINER_ATTRIBUTE;
        } else if (identity.assertAttributeValue(LEAD_AGGREGATOR_ATTRIBUTE, Boolean.TRUE.toString())) {
            return LEAD_AGGREGATOR_ATTRIBUTE;
        }
        return "unknown";
    }
    // ------------------- FINISH GENERAL ---------------------


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
