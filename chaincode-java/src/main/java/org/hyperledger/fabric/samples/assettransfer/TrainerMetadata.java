package org.hyperledger.fabric.samples.assettransfer;

import com.google.gson.Gson;
import com.owlike.genson.annotation.JsonProperty;
import org.hyperledger.fabric.contract.annotation.DataType;
import org.hyperledger.fabric.contract.annotation.Property;

@DataType()
public final class TrainerMetadata {
    @Property()
    private final String clientId;
    @Property()
    private final String checkedInTimestamp;

    public TrainerMetadata(@JsonProperty("clientId") final String clientId, @JsonProperty("checkedInTimestamp") final String checkedInTimestamp) {
        this.clientId = clientId;
        this.checkedInTimestamp = checkedInTimestamp;
    }

    public String getClientId() {
        return clientId;
    }

    public String getCheckedInTimestamp() {
        return checkedInTimestamp;
    }

    public String serialize() {
        return new Gson().toJson(this);
    }

    public static TrainerMetadata deserialize(final String json) {
        return new Gson().fromJson(json, TrainerMetadata.class);
    }
}
