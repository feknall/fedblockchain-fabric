package org.hyperledger.fabric.samples.assettransfer;

import com.owlike.genson.annotation.JsonProperty;
import org.hyperledger.fabric.contract.annotation.DataType;
import org.hyperledger.fabric.contract.annotation.Property;

@DataType()
public final class PersonalInfo {
    @Property()
    private final String clientId;
    @Property()
    private final String role;
    @Property()
    private final Boolean selectedForRound;
    @Property()
    private final String mspId;

    public PersonalInfo(@JsonProperty("clientId") final String clientId,
                        @JsonProperty("role") final String role,
                        @JsonProperty("selectedForRound") final Boolean selectedForRound,
                        @JsonProperty("mspId") final String mspId) {
        this.clientId = clientId;
        this.role = role;
        this.selectedForRound = selectedForRound;
        this.mspId = mspId;
    }

    public String getRole() {
        return role;
    }

    public Boolean isSelectedForRound() {
        return selectedForRound;
    }

    public String getClientId() {
        return clientId;
    }

    public String getMspId() {
        return mspId;
    }
}
