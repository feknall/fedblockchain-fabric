package org.hyperledger.fabric.samples.assettransfer;

import com.owlike.genson.annotation.JsonProperty;
import org.hyperledger.fabric.contract.annotation.DataType;
import org.hyperledger.fabric.contract.annotation.Property;

@DataType()
public final class PersonalInfo {
    @Property()
    private final String role;

    public PersonalInfo(@JsonProperty("role") final String role) {
        this.role = role;
    }

    public String getRole() {
        return role;
    }

}
