package net.nosial.spb.classes.interfaces;

import java.util.List;
import net.nosial.jfederation.records.ReportRecord;
import net.nosial.spb.objects.database.OperatorIdentity;

public interface ReportSource
{
    /**
     * Retrieves a list of reports that are currently open for the specified operator.
     *
     * @param identity the identity of the operator whose open reports are to be retrieved
     * @return a list of {@code ReportRecord} instances representing the currently open reports
     * @throws Exception if an error occurs during the retrieval of the reports
     */
    List<ReportRecord> openedReports(OperatorIdentity identity) throws Exception;
}
