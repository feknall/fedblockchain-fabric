/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hyperledger.fabric.samples.assettransfer;

import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.owlike.genson.Genson;
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
                        email = "a.transfer@example.com",
                        name = "Adrian Transfer",
                        url = "https://hyperledger.example.com")))
@Default
public final class Chaincode implements ContractInterface {

    private final Logger logger = Logger.getLogger(getClass().toString());

    private final Genson genson = new Genson();

    private enum AssetTransferErrors {
        ASSET_NOT_FOUND,
        ASSET_ALREADY_EXISTS,
        CLIENT_NOT_FOUND,
        CLIENT_ALREADY_EXISTS
    }

    /**
     * Creates some initial assets on the ledger.
     *
     * @param ctx the transaction context
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public void InitLedger(final Context ctx) {
        ChaincodeStub stub = ctx.getStub();

        CreateClient(ctx, "client1", "client1Name", "cin1", true);
        CreateClient(ctx, "client2", "client2Name", "cin2", true);
        CreateClient(ctx, "client3", "client3Name", "cin3", true);
        CreateClient(ctx, "client4", "client4Name", "cin4", true);
        CreateClient(ctx, "client5", "client5Name", "cin5", true);
        CreateClient(ctx, "client6", "client6Name", "cin6", true);

    }

    /**
     * Creates a new asset on the ledger.
     *
     * @param ctx the transaction context
     * @return the created asset
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public Model AddModelUpdate(final Context ctx, final String id, final String round, final String weights) {
        ChaincodeStub stub = ctx.getStub();

        try {
            byte[] weightsArr = BaseEncoding.base64().decode(weights);
            String hash = Hashing.sha256()
                    .hashBytes(weightsArr)
                    .toString();
            logger.log(Level.SEVERE, "Fuch me");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Fuch you", e);
        }


//
//        String uuid = "model-" + id + "-" + round;
//        try {
//            Files.write(Paths.get("/home/" + uuid), weightsArr);
//        } catch (IOException e) {
//            throw new ChaincodeException("Write file problem" + e.toString(), AssetTransferErrors.ASSET_NOT_FOUND.toString());
//        }
//
//        Model asset = new Model(id, round, hash, uuid);
//        String sortedJson = genson.serialize(asset);
//        stub.putStringState(uuid, sortedJson);

        return new Model(id, round, "1", "1");
    }

    /**
     * Retrieves an asset with the specified ID from the ledger.
     *
     * @param ctx     the transaction context
     * @param assetID the ID of the asset
     * @return the asset found on the ledger if there was one
     */
    @Transaction(intent = Transaction.TYPE.EVALUATE)
    public Model ReadAsset(final Context ctx, final String assetID) {
        ChaincodeStub stub = ctx.getStub();
        String assetJSON = stub.getStringState(assetID);

        if (assetJSON == null || assetJSON.isEmpty()) {
            String errorMessage = String.format("Asset %s does not exist", assetID);
            System.out.println(errorMessage);
            throw new ChaincodeException(errorMessage, AssetTransferErrors.ASSET_NOT_FOUND.toString());
        }

        Model asset = genson.deserialize(assetJSON, Model.class);
        return asset;
    }

//    /**
//     * Updates the properties of an asset on the ledger.
//     *
//     * @param ctx            the transaction context
//     * @param assetID        the ID of the asset being updated
//     * @param color          the color of the asset being updated
//     * @param size           the size of the asset being updated
//     * @param owner          the owner of the asset being updated
//     * @param appraisedValue the appraisedValue of the asset being updated
//     * @return the transferred asset
//     */
//    @Transaction(intent = Transaction.TYPE.SUBMIT)
//    public Model UpdateAsset(final Context ctx, final String assetID, final String color, final int size,
//                             final String owner, final int appraisedValue) {
//        ChaincodeStub stub = ctx.getStub();
//
//        if (!AssetExists(ctx, assetID)) {
//            String errorMessage = String.format("Asset %s does not exist", assetID);
//            System.out.println(errorMessage);
//            throw new ChaincodeException(errorMessage, AssetTransferErrors.ASSET_NOT_FOUND.toString());
//        }
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
//        String modelHash = "123";
////        String modelHash = Hashing.sha256()
////                .hashBytes(model)
////                .toString();
//
//        Model newAsset = new Model(assetID, color, size, owner, appraisedValue, modelHash, uuid);
//        //Use Genson to convert the Asset into string, sort it alphabetically and serialize it into a json string
//        String sortedJson = genson.serialize(newAsset);
//        stub.putStringState(assetID, sortedJson);
//        return newAsset;
//    }

    /**
     * Deletes asset on the ledger.
     *
     * @param ctx     the transaction context
     * @param assetID the ID of the asset being deleted
     */
    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public void DeleteAsset(final Context ctx, final String assetID) {
        ChaincodeStub stub = ctx.getStub();

        if (!AssetExists(ctx, assetID)) {
            String errorMessage = String.format("Asset %s does not exist", assetID);
            System.out.println(errorMessage);
            throw new ChaincodeException(errorMessage, AssetTransferErrors.ASSET_NOT_FOUND.toString());
        }

        stub.delState(assetID);
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
        ChaincodeStub stub = ctx.getStub();

        List<Model> queryResults = new ArrayList<Model>();

        // To retrieve all assets from the ledger use getStateByRange with empty startKey & endKey.
        // Giving empty startKey & endKey is interpreted as all the keys from beginning to end.
        // As another example, if you use startKey = 'asset0', endKey = 'asset9' ,
        // then getStateByRange will retrieve asset with keys between asset0 (inclusive) and asset9 (exclusive) in lexical order.
        QueryResultsIterator<KeyValue> results = stub.getStateByRange("", "");

        for (KeyValue result : results) {
            Model asset = genson.deserialize(result.getStringValue(), Model.class);
            System.out.println(asset);
            queryResults.add(asset);
        }

        final String response = genson.serialize(queryResults);

        return response;
    }


    @Transaction(intent = Transaction.TYPE.SUBMIT)
    public Client CreateClient(final Context ctx, final String clientID, final String name,
                               final String cinNumber, final boolean active) {
        ChaincodeStub stub = ctx.getStub();

        if (ClientExists(ctx, clientID)) {
            String errorMessage = String.format("Client %s already exists", clientID);
            System.out.println(errorMessage);
            throw new ChaincodeException(errorMessage, AssetTransferErrors.CLIENT_ALREADY_EXISTS.toString());
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
        String sortedJson = genson.serialize(client);
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
            throw new ChaincodeException(errorMessage, AssetTransferErrors.CLIENT_NOT_FOUND.toString());
        }

        return genson.deserialize(clientJSON, Client.class);
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
            throw new ChaincodeException(errorMessage, AssetTransferErrors.CLIENT_NOT_FOUND.toString());
        }

        Client newClient = new Client(clientID, name, cinNumber, active);

        String sortedJson = genson.serialize(newClient);
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
            throw new ChaincodeException(errorMessage, AssetTransferErrors.CLIENT_NOT_FOUND.toString());
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
            throw new ChaincodeException(errorMessage, AssetTransferErrors.CLIENT_NOT_FOUND.toString());
        }

        Client client = genson.deserialize(clientJSON, Client.class);

        Client newClient = new Client(client.getClientID(), client.getName(), client.getCinNumber(), alive);
        String sortedJson = genson.serialize(newClient);
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
            Client client = genson.deserialize(result.getStringValue(), Client.class);
            System.out.println(client);
            queryResults.add(client);
        }

        return genson.serialize(queryResults);
    }
}
