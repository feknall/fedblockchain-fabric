/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hyperledger.fabric.samples.assettransfer;

import com.google.gson.Gson;
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
                title = "Asset Transfer",
                description = "The hyperlegendary asset transfer",
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
        CLIENT_NOT_FOUND,
        CLIENT_ALREADY_EXISTS,
        INVALID_ACCESS
    }

    static final String MODEL_METADATA_KEY_PREFIX = "modelMetadata";
    static final String MODEL_SECRET_KEY_PREFIX = "modelSecret";
    static final String END_ROUND_MODEL_KEY_PREFIX = "endRoundModel";
    static final String AGGREGATED_MODEL_SECRET_PREFIX = "aggregatedModelSecret";
    static final String AGGREGATED_MODEL_COLLECTION_NAME = "aggregatedModelCollection";

    static final String AGGREGATOR_ATTRIBUTE = "aggregator";
    static final String LEAD_AGGREGATOR_ATTRIBUTE = "leadAggregator";
    static final String TRAINER_ATTRIBUTE = "trainer";

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public void InitLedger(final Context ctx) {

    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public ModelMetadata CreateModelMetadata(final Context ctx, final String modelId, final String modelName) {
        ChaincodeStub stub = ctx.getStub();

        if (ModelExists(ctx, modelId)) {
            String errorMessage = String.format("ModelMetadata %s already exists", modelId);
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.MODEL_METADATA_ALREADY_EXISTS.toString());
        }

        ModelMetadata model = new ModelMetadata(modelId, modelName);
        String json = model.serialize();
        String key = stub.createCompositeKey(MODEL_METADATA_KEY_PREFIX, modelId).toString();
        stub.putStringState(key, json);
        return model;
    }


    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public ModelMetadata AddModelSecret(final Context ctx, final String modelId, final String round, final String weights) {
        checkHasTrainerRoleOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();

        String modelJson = toJsonIfModelMetadataExistsOrThrow(stub, modelId);
        ModelMetadata modelMetadata = ModelMetadata.deserialize(modelJson);

        ModelUpdate modelUpdate = new ModelUpdate(modelId, round, weights);
        String modelUpdateJson = modelUpdate.serialize();

        String collectionName = getModelSecretCollectionName(ctx);
        String key = stub.createCompositeKey(MODEL_SECRET_KEY_PREFIX, modelId, round, ctx.getClientIdentity().getId()).toString();
        stub.putPrivateData(collectionName, key, modelUpdateJson);
        logger.log(Level.INFO, "ModelUpdate " + key + " stored successfully in " + collectionName);
        logger.log(Level.INFO, "ModelUpdate JSON: " + modelUpdateJson);
        return modelMetadata;
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public ModelMetadata AddEndRoundModel(final Context ctx, final String modelId, final String round, final String weights) {
        checkHasLeadAggregatorRoleOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();

        String modelJson = toJsonIfModelMetadataExistsOrThrow(stub, modelId);
        ModelMetadata modelMetadata = ModelMetadata.deserialize(modelJson);

        ModelUpdate modelUpdate = new ModelUpdate(modelId, round, weights);
        String modelUpdateJson = modelUpdate.serialize();

        String key = stub.createCompositeKey(END_ROUND_MODEL_KEY_PREFIX, modelId, round).toString();
        stub.putStringState(key, modelUpdateJson);
        logger.log(Level.INFO, "ModelUpdate " + key + " stored successfully in public :)");
        logger.log(Level.INFO, "ModelUpdate JSON: " + modelUpdateJson);
        return modelMetadata;
    }

    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public ModelMetadata AddAggregatedSecret(final Context ctx, final String modelId, final String round, final String weights) {
        checkHasAggregatorRoleOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();

        String modelJson = toJsonIfModelMetadataExistsOrThrow(stub, modelId);
        ModelMetadata modelMetadata = ModelMetadata.deserialize(modelJson);

        ModelUpdate aggregatedModelUpdate = new ModelUpdate(modelId, round, weights);
        String modelUpdateJson = aggregatedModelUpdate.serialize();

        String key = stub.createCompositeKey(AGGREGATED_MODEL_SECRET_PREFIX, modelId, round, ctx.getClientIdentity().getId()).toString();
        stub.putPrivateData(AGGREGATED_MODEL_COLLECTION_NAME, key, modelUpdateJson);
        logger.log(Level.INFO, "AggregatedModelUpdate " + key + " stored successfully in " + AGGREGATED_MODEL_COLLECTION_NAME);
        logger.log(Level.INFO, "ModelUpdate JSON: " + modelUpdateJson);
        return modelMetadata;
    }


    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public ModelUpdate[] ReadAggregatedModelUpdate(final Context ctx, final String modelId, final String round) throws Exception {
        checkHasLeadAggregatorRoleOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();
        CompositeKey key = stub.createCompositeKey(AGGREGATED_MODEL_SECRET_PREFIX, modelId, round);

        String collectionName = getModelSecretCollectionName(ctx);

        List<ModelUpdate> modelUpdateList = new ArrayList<>();
        try (QueryResultsIterator<KeyValue> results = stub.getPrivateDataByPartialCompositeKey(collectionName, key)) {
            for (KeyValue result : results) {
                if (result.getStringValue() == null || result.getStringValue().length() == 0) {
                    logger.log(Level.SEVERE, "Invalid AggregatedModelUpdate json: %s\n", result.getStringValue());
                    continue;
                }
                ModelUpdate modelUpdate = ModelUpdate.deserialize(result.getStringValue());
                modelUpdateList.add(modelUpdate);
                logger.log(Level.INFO, String.format("Round %s of Model %s read successfully", modelUpdate.getRound(), modelUpdate.getModelId()));
            }
        }

        return modelUpdateList.toArray(new ModelUpdate[0]);
    }


    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public ModelUpdate[] ReadModelSecrets(final Context ctx, final String modelId, final String round) throws Exception {
        checkHasAggregatorRoleOrThrow(ctx);

        ChaincodeStub stub = ctx.getStub();
        String collectionName = getModelSecretCollectionName(ctx);
        CompositeKey key = stub.createCompositeKey(MODEL_SECRET_KEY_PREFIX, modelId, round);
        List<ModelUpdate> modelUpdateList = new ArrayList<>();
        try (QueryResultsIterator<KeyValue> results = stub.getPrivateDataByPartialCompositeKey(collectionName, key)) {
            for (KeyValue result : results) {
                if (result.getStringValue() == null || result.getStringValue().length() == 0) {
                    logger.log(Level.SEVERE, "Invalid ModelUpdate json: %s\n", result.getStringValue());
                    continue;
                }
                ModelUpdate modelUpdate = ModelUpdate.deserialize(result.getStringValue());
                modelUpdateList.add(modelUpdate);
                logger.log(Level.INFO, String.format("Round %s of Model %s read successfully", modelUpdate.getRound(), modelUpdate.getModelId()));
            }
        }

        return modelUpdateList.toArray(new ModelUpdate[0]);
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public ModelUpdate ReadEndRoundModel(final Context ctx, final String modelId, final String round) throws Exception {
        ChaincodeStub stub = ctx.getStub();

        CompositeKey key = stub.createCompositeKey(END_ROUND_MODEL_KEY_PREFIX, modelId, round);

        String modelEndRoundJson = stub.getStringState(key.toString());
        if (modelEndRoundJson == null || modelEndRoundJson.length() == 0) {
            String errorMessage = String.format("EndRoundModel not found. modelId: %s, round: %s", modelId, round);
            logger.log(Level.SEVERE, errorMessage, modelEndRoundJson);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.INVALID_ACCESS.toString());
        }

        ModelUpdate endRoundModelUpdate = ModelUpdate.deserialize(modelEndRoundJson);
        logger.log(Level.INFO, "EndRoundModel for round %s of model %s");

        return endRoundModelUpdate;
    }
//
//    @Transaction(intent = Transaction.TYPE.SUBMIT)
//    public void DeleteModel(final Context ctx, final String uuid) {
//        ChaincodeStub stub = ctx.getStub();
//
//        if (!AssetExists(ctx, assetID)) {
//            String errorMessage = String.format("Asset %s does not exist", assetID);
//            System.out.println(errorMessage);
//            throw new ChaincodeException(errorMessage, ChaincodeErrors.MODEL_NOT_FOUND.toString());
//        }
//
//        stub.delState(assetID);
//    }

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
            String errorMessage = "User " + ctx.getClientIdentity().getId() + "has no AGGREGATOR attribute";
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.INVALID_ACCESS.toString());
        }
    }

    public void checkHasLeadAggregatorRoleOrThrow(final Context ctx) {
        if (ctx.getClientIdentity().assertAttributeValue(LEAD_AGGREGATOR_ATTRIBUTE, Boolean.FALSE.toString())) {
            String errorMessage = "User " + ctx.getClientIdentity().getId() + "has no LEAD_AGGREGATOR attribute";
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.INVALID_ACCESS.toString());
        }
    }

    public void checkHasTrainerRoleOrThrow(final Context ctx) {
        if (ctx.getClientIdentity().assertAttributeValue(TRAINER_ATTRIBUTE, Boolean.FALSE.toString())) {
            String errorMessage = "User " + ctx.getClientIdentity().getId() + "has no TRAINER attribute";
            logger.log(Level.SEVERE, errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.INVALID_ACCESS.toString());
        }
    }

    /**
     * Checks the existence of the asset on the ledger
     *
     * @param ctx     the transaction context
     * @param assetID the ID of the asset
     * @return boolean indicating the existence of the asset
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public boolean AssetExists(final Context ctx, final String assetID) {
        ChaincodeStub stub = ctx.getStub();

        String assetJSON = stub.getStringState(assetID);

        return (assetJSON != null && !assetJSON.isEmpty());
    }

//    /**
//     * Changes the owner of a asset on the ledger.
//     *
//     * @param ctx      the transaction context
//     * @param assetID  the ID of the asset being transferred
//     * @param newOwner the new owner
//     * @return the old owner
//     */
//    @Transaction(intent = Transaction.TYPE.SUBMIT)
//    public String TransferAsset(final Context ctx, final String assetID, final String newOwner) {
//        ChaincodeStub stub = ctx.getStub();
//        String assetJSON = stub.getStringState(assetID);
//
//        if (assetJSON == null || assetJSON.isEmpty()) {
//            String errorMessage = String.format("Asset %s does not exist", assetID);
//            System.out.println(errorMessage);
//            throw new ChaincodeException(errorMessage, AssetTransferErrors.ASSET_NOT_FOUND.toString());
//        }
//
//        Model asset = genson.deserialize(assetJSON, Model.class);
//
//        Random random = new Random();
//        byte[] model = new byte[10000000];
//        random.nextBytes(model);
//        String uuid = "123456789";
//        try {
//            Files.write(Paths.get("/home/" + uuid), model);
//        } catch (IOException e) {
//            throw new ChaincodeException("Write file problem" + e.toString(), AssetTransferErrors.ASSET_NOT_FOUND.toString());
//        }
//
////        String modelHash = Hashing.sha256()
////                .hashBytes(model)
////                .toString();
//        String modelHash = "123";
//        Model newAsset = new Model(asset.getId(), asset.getColor(), asset.getSize(), newOwner,
//                asset.getAppraisedValue(), modelHash, uuid);
//        //Use a Genson to conver the Asset into string, sort it alphabetically and serialize it into a json string
//        String sortedJson = genson.serialize(newAsset);
//        stub.putStringState(assetID, sortedJson);
//
//        return asset.getOwner();
//    }

    /**
     * Retrieves all assets from the ledger.
     *
     * @param ctx the transaction context
     * @return array of assets found on the ledger
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String GetAllAssets(final Context ctx) {
        if (ctx.getClientIdentity().assertAttributeValue(AGGREGATOR_ATTRIBUTE, "true")) {
            return "You are a fucking aggregator!";
        } else {
            return "You are not a aggregator";
        }
//        ChaincodeStub stub = ctx.getStub();
//
//        List<Model> queryResults = new ArrayList<Model>();
//
//        // To retrieve all assets from the ledger use getStateByRange with empty startKey & endKey.
//        // Giving empty startKey & endKey is interpreted as all the keys from beginning to end.
//        // As another example, if you use startKey = 'asset0', endKey = 'asset9' ,
//        // then getStateByRange will retrieve asset with keys between asset0 (inclusive) and asset9 (exclusive) in lexical order.
//        QueryResultsIterator<KeyValue> results = stub.getStateByRange("", "");
//
//        for (KeyValue result : results) {
//            Model asset = new Gson().fromJson(result.getStringValue(), Model.class);
//            System.out.println(asset);
//            queryResults.add(asset);
//        }
//
//        final String response = new Gson().toJson(queryResults);
//
//        return response;
    }


    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public Client CreateClient(final Context ctx, final String clientID, final String name,
                               final String cinNumber, final boolean active) {
        ChaincodeStub stub = ctx.getStub();

        if (ClientExists(ctx, clientID)) {
            String errorMessage = String.format("Client %s already exists", clientID);
            System.out.println(errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.CLIENT_ALREADY_EXISTS.toString());
        }

//        Random random = new Random();
//        byte[] model = new byte[10000000];
//        random.nextBytes(model);
//        String uuid = "123456789";
//        String modelHash = "123";
//        try {
//            modelHash = "321";
//            Files.write(Paths.get("/home/" + uuid), model);
//            modelHash = "000";
//        } catch (IOException e) {
//            throw new ChaincodeException("Write file problem" + e.toString(), AssetTransferErrors.ASSET_NOT_FOUND.toString());
//        }


//        String modelHash = Hashing.sha256()
//                .hashBytes(model)
//                .toString();

        Client client = new Client(clientID, name, cinNumber, active);
        //Use Genson to convert the Asset into string, sort it alphabetically and serialize it into a json string
        String sortedJson = new Gson().toJson(client);
        stub.putStringState(clientID, sortedJson);

        return client;
    }

    /**
     * Retrieves an asset with the specified ID from the ledger.
     *
     * @param ctx      the transaction context
     * @param clientID the ID of the asset
     * @return the asset found on the ledger if there was one
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public Client ReadClient(final Context ctx, final String clientID) {
        ChaincodeStub stub = ctx.getStub();
        String clientJSON = stub.getStringState(clientID);

        if (clientJSON == null || clientJSON.isEmpty()) {
            String errorMessage = String.format("Client %s does not exist", clientID);
            System.out.println(errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.CLIENT_NOT_FOUND.toString());
        }

        return new Gson().fromJson(clientJSON, Client.class);
    }

    /**
     * Updates the properties of an asset on the ledger.
     *
     * @param ctx the transaction context
     * @return the transferred asset
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public Client UpdateClient(final Context ctx, final String clientID, final String name,
                               final String cinNumber, final boolean active) {
        ChaincodeStub stub = ctx.getStub();

        if (!ClientExists(ctx, clientID)) {
            String errorMessage = String.format("Client %s does not exist", clientID);
            System.out.println(errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.CLIENT_NOT_FOUND.toString());
        }

        Client newClient = new Client(clientID, name, cinNumber, active);

        String sortedJson = new Gson().toJson(newClient);
        stub.putStringState(clientID, sortedJson);
        return newClient;
    }

    /**
     * Deletes asset on the ledger.
     *
     * @param ctx      the transaction context
     * @param clientID the ID of the asset being deleted
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public void DeleteClient(final Context ctx, final String clientID) {
        ChaincodeStub stub = ctx.getStub();

        if (!ClientExists(ctx, clientID)) {
            String errorMessage = String.format("Client %s does not exist", clientID);
            System.out.println(errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.CLIENT_NOT_FOUND.toString());
        }

        stub.delState(clientID);
    }

    /**
     * Checks the existence of the asset on the ledger
     *
     * @param ctx      the transaction context
     * @param clientID the ID of the asset
     * @return boolean indicating the existence of the asset
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public boolean ClientExists(final Context ctx, final String clientID) {
        ChaincodeStub stub = ctx.getStub();
        String clientJson = stub.getStringState(clientID);

        return (clientJson != null && !clientJson.isEmpty());
    }

    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public boolean ModelExists(final Context ctx, final String modelId) {
        ChaincodeStub stub = ctx.getStub();
        String modelJson = stub.getStringState(modelId);

        return (modelJson != null && !modelJson.isEmpty());
    }

    /**
     * Changes the owner of a asset on the ledger.
     *
     * @param ctx the transaction context
     * @return the old owner
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public boolean ChangeAlive(final Context ctx, final String clientID, final boolean alive) {
        ChaincodeStub stub = ctx.getStub();
        String clientJSON = stub.getStringState(clientID);

        if (clientJSON == null || clientJSON.isEmpty()) {
            String errorMessage = String.format("Client %s does not exist", clientID);
            System.out.println(errorMessage);
            throw new ChaincodeException(errorMessage, ChaincodeErrors.CLIENT_NOT_FOUND.toString());
        }

        Client client = new Gson().fromJson(clientJSON, Client.class);

        Client newClient = new Client(client.getClientID(), client.getName(), client.getCinNumber(), alive);
        String sortedJson = new Gson().toJson(newClient);
        stub.putStringState(clientID, sortedJson);

        return newClient.isAlive();
    }

    /**
     * Retrieves all assets from the ledger.
     *
     * @param ctx the transaction context
     * @return array of assets found on the ledger
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public String GetAllClients(final Context ctx) {
        ChaincodeStub stub = ctx.getStub();

        List<Client> queryResults = new ArrayList<>();

        // To retrieve all assets from the ledger use getStateByRange with empty startKey & endKey.
        // Giving empty startKey & endKey is interpreted as all the keys from beginning to end.
        // As another example, if you use startKey = 'asset0', endKey = 'asset9' ,
        // then getStateByRange will retrieve asset with keys between asset0 (inclusive) and asset9 (exclusive) in lexical order.
        QueryResultsIterator<KeyValue> results = stub.getStateByRange("client*", "");

        for (KeyValue result : results) {
            Client client = new Gson().fromJson(result.getStringValue(), Client.class);
            System.out.println(client);
            queryResults.add(client);
        }

        return new Gson().toJson(queryResults);
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
