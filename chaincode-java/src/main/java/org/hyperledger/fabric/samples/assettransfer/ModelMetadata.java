/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hyperledger.fabric.samples.assettransfer;

import com.google.gson.Gson;
import com.owlike.genson.annotation.JsonProperty;
import org.hyperledger.fabric.contract.annotation.DataType;
import org.hyperledger.fabric.contract.annotation.Property;

@DataType()
public final class ModelMetadata {

    @Property()
    private final String modelId;
    @Property()
    private final String name;

    @Property()
    private final String clientsPerRound;

    @Property()
    private final String secretsPerClient;


    public ModelMetadata(@JsonProperty("modelId") final String modelId,
                         @JsonProperty("name") final String name,
                         @JsonProperty("clientsPerRound") final String clientsPerRound,
                         @JsonProperty("secretsPerClient") final String secretsPerClient) {
        this.modelId = modelId;
        this.name = name;
        this.clientsPerRound = clientsPerRound;
        this.secretsPerClient = secretsPerClient;
    }

    public String getModelId() {
        return modelId;
    }

    public String getClientsPerRound() {
        return clientsPerRound;
    }

    public String getSecretsPerClient() {
        return secretsPerClient;
    }

    public String getName() {
        return name;
    }


    public String serialize() {
        return new Gson().toJson(this);
    }

    public static ModelMetadata deserialize(final String ser) {
        return new Gson().fromJson(ser, ModelMetadata.class);
    }


}
