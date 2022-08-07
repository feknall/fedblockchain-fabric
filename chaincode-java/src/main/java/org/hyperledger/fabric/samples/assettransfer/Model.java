/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hyperledger.fabric.samples.assettransfer;

import com.owlike.genson.annotation.JsonProperty;
import org.hyperledger.fabric.contract.annotation.DataType;
import org.hyperledger.fabric.contract.annotation.Property;

import java.util.Objects;

@DataType()
public final class Model {

    @Property()
    private final String id;
    @Property()
    private final String round;

    @Property()
    private final String hash;

    @Property()
    private final String uuid;

    public String getRound() {
        return round;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Model model = (Model) o;
        return Objects.equals(id, model.id) && Objects.equals(round, model.round) && Objects.equals(hash, model.hash) && Objects.equals(uuid, model.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, round, hash, uuid);
    }


    public Model(@JsonProperty("id") final String id, final String round, @JsonProperty("hash") final String hash, @JsonProperty("uuid") final String uuid) {
        this.id = id;
        this.round = round;
        this.hash = hash;
        this.uuid = uuid;
    }

    public String getId() {
        return id;
    }


    public String getHash() {
        return hash;
    }

    public String getUuid() {
        return uuid;
    }

    @Override
    public String toString() {
        return "Model{"
                + "id='" + id
                + ", hash='" + hash
                + ", uuid='" + uuid
                + '}';
    }

}
