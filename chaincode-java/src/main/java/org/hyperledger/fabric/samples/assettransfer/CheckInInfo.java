package org.hyperledger.fabric.samples.assettransfer;

import com.owlike.genson.annotation.JsonProperty;
import org.hyperledger.fabric.contract.annotation.DataType;
import org.hyperledger.fabric.contract.annotation.Property;

import java.util.List;

@DataType()
public final class CheckInInfo {

    @Property()
    private final List<TrainerMetadata> checkedInTrainers;

    public CheckInInfo(@JsonProperty("checkedInTrainers") final List<TrainerMetadata> checkedInTrainers) {
        this.checkedInTrainers = checkedInTrainers;
    }

    public List<TrainerMetadata> getCheckedInTrainers() {
        return checkedInTrainers;
    }
}
