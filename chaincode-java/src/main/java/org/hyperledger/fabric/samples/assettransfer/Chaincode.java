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
        INVALID_ACCESS
    }

    static final String MODEL_METADATA_KEY = "modelMetadataKey";
    static final String MODEL_SECRET_KEY = "modelSecretKey";
    static final String MODEL_SECRET_COUNTER_KEY = "modelSecretCounterKey";
    static final String AGGREGATED_SECRET_COUNTER_KEY = "aggregatedSecretCounterKey";
    static final String END_ROUND_MODEL_KEY = "endRoundModelKey";
    static final String AGGREGATED_SECRET_KEY = "aggregatedSecretKey";

    static final String AGGREGATED_SECRET_COLLECTION = "aggregatedSecretCollection";

    static final String AGGREGATOR_ATTRIBUTE = "aggregator";
    static final String LEAD_AGGREGATOR_ATTRIBUTE = "leadAggregator";
    static final String TRAINER_ATTRIBUTE = "trainer";

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

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public void InitLedger(final Context ctx) {

    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public ModelMetadata StartTraining(final Context ctx, final String modelId) {
        ChaincodeStub stub = ctx.getStub();
        if (!ModelExists(ctx, modelId)) {
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
        return newModelMetadata;
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

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public ModelMetadata CreateModelMetadata(final Context ctx, final String modelId,
                                             final String modelName, final String clientsPerRound,
                                             final String secretsPerClient, final String trainingRounds) {
        ChaincodeStub stub = ctx.getStub();

        if (ModelExists(ctx, modelId)) {
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

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public ModelMetadata AddModelSecret(final Context ctx, final String modelId, final String round, final String weights) {
        checkHasTrainerRoleOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();

        String modelMetadataJson = toJsonIfModelMetadataExistsOrThrow(stub, modelId);
        ModelMetadata modelMetadata = ModelMetadata.deserialize(modelMetadataJson);

        ModelSecret modelSecret = new ModelSecret(modelId, round, weights);
        String modelSecretJson = modelSecret.serialize();

        String collectionName = getModelSecretCollectionName(ctx);
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
        }

        logger.log(Level.INFO, "ModelUpdate " + key + " stored successfully in public :)");
        logger.log(Level.INFO, "ModelUpdate JSON: " + modelUpdateJson);
        return modelMetadata;
    }

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
    public AggregatedSecret[] ReadAggregatedSecret(final Context ctx, final String modelId, final String round) throws Exception {
        checkHasLeadAggregatorRoleOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();
        CompositeKey key = stub.createCompositeKey(AGGREGATED_SECRET_KEY, modelId, round);

        String collectionName = getModelSecretCollectionName(ctx);

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

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public ModelSecret[] ReadModelSecrets(final Context ctx, final String modelId, final String round) throws Exception {
        checkHasAggregatorRoleOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();
        String collectionName = getModelSecretCollectionName(ctx);
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

    public String toJsonIfModelMetadataExistsOrThrow(final ChaincodeStub stub, final String modelId) {
        String modelJson = stub.getStringState(modelId);
        if (modelJson == null || modelJson.isEmpty()) {
            String errorMessage = String.format("ModelMetadata %s does not exist", modelId);
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.MODEL_NOT_FOUND.toString());
        }
        logger.log(Level.INFO, "ModelMetadata Json: " + modelJson);
        return modelJson;
    }

    public void checkHasAggregatorRoleOrThrow(final Context ctx) {
        if (ctx.getClientIdentity().assertAttributeValue(AGGREGATOR_ATTRIBUTE, Boolean.FALSE.toString())) {
            String errorMessage = "User " + ctx.getClientIdentity().getId() + "has no aggregator attribute";
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.INVALID_ACCESS.toString());
        }
    }

    public void checkHasLeadAggregatorRoleOrThrow(final Context ctx) {
        if (ctx.getClientIdentity().assertAttributeValue(LEAD_AGGREGATOR_ATTRIBUTE, Boolean.FALSE.toString())) {
            String errorMessage = "User " + ctx.getClientIdentity().getId() + "has no leadAggregator attribute";
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.INVALID_ACCESS.toString());
        }
    }

    public void checkHasTrainerRoleOrThrow(final Context ctx) {
        if (ctx.getClientIdentity().assertAttributeValue(TRAINER_ATTRIBUTE, Boolean.FALSE.toString())) {
            String errorMessage = "User " + ctx.getClientIdentity().getId() + "has no trainer attribute";
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.INVALID_ACCESS.toString());
        }
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public boolean AssetExists(final Context ctx, final String assetID) {
        ChaincodeStub stub = ctx.getStub();

        String assetJSON = stub.getStringState(assetID);

        return (assetJSON != null && !assetJSON.isEmpty());
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public PersonalInfo GetRoleInCertificate(final Context ctx) {
        if (ctx.getClientIdentity().assertAttributeValue(AGGREGATOR_ATTRIBUTE, Boolean.TRUE.toString())) {
            return new PersonalInfo(AGGREGATOR_ATTRIBUTE);
        } else if (ctx.getClientIdentity().assertAttributeValue(TRAINER_ATTRIBUTE, Boolean.TRUE.toString())) {
            return new PersonalInfo(TRAINER_ATTRIBUTE);
        } else if (ctx.getClientIdentity().assertAttributeValue(LEAD_AGGREGATOR_ATTRIBUTE, Boolean.TRUE.toString())) {
            return new PersonalInfo(LEAD_AGGREGATOR_ATTRIBUTE);
        }
        return new PersonalInfo("unknown");
    }


    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public boolean ModelExists(final Context ctx, final String modelId) {
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

    private String getModelSecretCollectionName(final Context ctx) {
        // Get the MSP ID of submitting client identity
        String clientMSPID = ctx.getClientIdentity().getMSPID();
        // Create the collection name
        return clientMSPID + "PrivateCollection";
    }


}
