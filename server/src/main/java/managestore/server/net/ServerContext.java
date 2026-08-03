package managestore.server.net;

import com.google.gson.Gson;
import managestore.common.model.StoreChain;
import managestore.server.repository.EmployeeRepository;
import managestore.server.repository.SalesRecordRepository;
import managestore.server.report.WordReportExporter;
import managestore.server.service.AuthService;
import managestore.server.service.ChatMediator;
import managestore.server.service.PurchaseService;
import managestore.server.service.ReportService;

/** Shared state every {@link ClientHandler} thread needs a reference to. */
public class ServerContext {

    private final StoreChain storeChain;
    private final AuthService authService;
    private final EmployeeRepository employeeRepository;
    private final PurchaseService purchaseService = new PurchaseService();
    private final ChatMediator chatMediator = new ChatMediator();
    private final SalesRecordRepository salesRecordRepository = new SalesRecordRepository();
    private final ReportService reportService;
    private final Gson gson;

    public ServerContext(StoreChain storeChain, AuthService authService, EmployeeRepository employeeRepository, Gson gson) {
        this.storeChain = storeChain;
        this.authService = authService;
        this.employeeRepository = employeeRepository;
        this.gson = gson;
        this.reportService = new ReportService(storeChain, new WordReportExporter());
    }

    public StoreChain getStoreChain() {
        return storeChain;
    }

    public AuthService getAuthService() {
        return authService;
    }

    public EmployeeRepository getEmployeeRepository() {
        return employeeRepository;
    }

    public PurchaseService getPurchaseService() {
        return purchaseService;
    }

    public ChatMediator getChatMediator() {
        return chatMediator;
    }

    public SalesRecordRepository getSalesRecordRepository() {
        return salesRecordRepository;
    }

    public ReportService getReportService() {
        return reportService;
    }

    public Gson getGson() {
        return gson;
    }
}
