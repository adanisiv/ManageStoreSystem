package managestore.server.repository;

import managestore.common.model.SalesRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory only, deliberately: {@link SalesRecord} holds a {@link
 * managestore.common.model.Customer}, and Customer is a polymorphic type
 * (NewCustomer/ReturningCustomer/VIPCustomer). Gson can serialize a concrete
 * instance fine but can't reliably deserialize back into "the right
 * subclass" from JSON alone (see {@link managestore.common.protocol.CustomerDto}'s
 * javadoc for the same issue) — so unlike the other repositories, this one
 * isn't backed by a JSON file. Sales history resets on server restart; the
 * brief doesn't require it to survive one.
 */
public class SalesRecordRepository {

    private final List<SalesRecord> records = new CopyOnWriteArrayList<>();

    public void add(SalesRecord record) {
        records.add(record);
    }

    public List<SalesRecord> all() {
        return new ArrayList<>(records);
    }
}
