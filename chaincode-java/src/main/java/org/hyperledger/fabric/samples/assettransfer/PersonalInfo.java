package org.hyperledger.fabric.samples.assettransfer;

import com.owlike.genson.annotation.JsonProperty;
import org.hyperledger.fabric.contract.annotation.DataType;
import org.hyperledger.fabric.contract.annotation.Property;

@DataType()
public final class PersonalInfo {
    @Property()
    private final String role;
    @Property()
    private final Boolean selectedForRound;

    public PersonalInfo(@JsonProperty("role") final String role, @JsonProperty("selectedForRound") final Boolean selectedForRound) {
        this.role = role;
        this.selectedForRound = selectedForRound;
    }

    public String getRole() {
        return role;
    }

    public Boolean isSelectedForRound() {
        return selectedForRound;
    }
}
