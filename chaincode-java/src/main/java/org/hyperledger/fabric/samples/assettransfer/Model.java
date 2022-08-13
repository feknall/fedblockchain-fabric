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
public final class Model {

    @Property()
    private final String modelId;
    @Property()
    private final String name;


    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Model model = (Model) o;
        return Objects.equals(modelId, model.modelId) && Objects.equals(name, model.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelId, name);
    }


    public Model(@JsonProperty("modelId") final String modelId, @JsonProperty("name") final String name) {
        this.modelId = modelId;
        this.name = name;
    }

    public String getModelId() {
        return modelId;
    }


    public String getName() {
        return name;
    }


    @Override
    public String toString() {
        return "Model{"
                + "modelId='" + modelId
                + ", name='" + name
                + '}';
    }

    public String serialize() {
        return new Gson().toJson(this);
    }

    public static Model deserialize(final String ser) {
        return new Gson().fromJson(ser, Model.class);
    }


}
