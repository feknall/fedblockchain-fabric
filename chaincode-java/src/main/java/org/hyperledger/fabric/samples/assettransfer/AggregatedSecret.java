/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hyperledger.fabric.samples.assettransfer;

import com.google.gson.Gson;
import com.owlike.genson.annotation.JsonProperty;
import org.hyperledger.fabric.contract.annotation.DataType;
import org.hyperledger.fabric.contract.annotation.Property;

import java.util.Objects;

@DataType()
public final class AggregatedSecret {

    @Property()
    private final String modelId;
    @Property()
    private final String round;
    @Property()
    private final String weights;

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AggregatedSecret modelSecret = (AggregatedSecret) o;
        return Objects.equals(modelId, modelSecret.modelId) && Objects.equals(round, modelSecret.round) && Objects.equals(weights, modelSecret.weights);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelId, round, weights);
    }


    public AggregatedSecret(@JsonProperty("modelId") final String modelId, @JsonProperty("round") final String round, @JsonProperty("weights") final String weights) {
        this.modelId = modelId;
        this.round = round;
        this.weights = weights;
    }

    public String getModelId() {
        return modelId;
    }


    public String getRound() {
        return round;
    }

    public String getWeights() {
        return weights;
    }

    @Override
    public String toString() {
        return "Model{"
                + "id='" + modelId
                + ", round='" + round
                + ", weights='" + weights
                + '}';
    }

    public String serialize() {
        return new Gson().toJson(this);
    }

    public static AggregatedSecret deserialize(final String json) {
        return new Gson().fromJson(json, AggregatedSecret.class);
    }

}
