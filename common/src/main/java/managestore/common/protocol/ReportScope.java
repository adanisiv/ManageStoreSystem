package managestore.common.protocol;

/** What to group a sales report by. ALL means one grand total, no grouping. */
public enum ReportScope {
    ALL,
    BRANCH,
    PRODUCT,
    CATEGORY
}
