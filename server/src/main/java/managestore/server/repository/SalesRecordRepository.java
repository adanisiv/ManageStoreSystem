package managestore.server.repository;

import managestore.common.model.SalesRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * This repository only keeps data in memory, on purpose. Here is why.
 *
 * <p>A {@link SalesRecord} holds a {@link managestore.common.model.Customer},
 * and Customer actually has several different subclasses (NewCustomer,
 * ReturningCustomer, VIPCustomer). Gson can turn one of these objects into
 * JSON without trouble. But when reading that JSON back, Gson cannot reliably
 * tell which subclass to rebuild it as (see {@link managestore.common.protocol.CustomerDto}'s
 * javadoc — it hits the same problem). So, unlike the other repositories,
 * this one is not backed by a JSON file.
 *
 * <p>The result: sales history is lost when the server restarts. We accept
 * that trade-off here, instead of writing extra code to save and load each
 * customer subclass correctly for data that does not need to survive a restart.
 */
public class SalesRecordRepository {

    private final List<SalesRecord> records = new CopyOnWriteArrayList<>();

    public void add(SalesRecord record) {
        records.add(record);
    }

    // Returns a copy of the list, not the original. This way the caller can
    // freely read through it without two things happening: later additions
    // showing up in their copy, or the caller accidentally changing our
    // internal data.
    public List<SalesRecord> all() {
        return new ArrayList<>(records);
    }
}
