/**
 * Copyright (c) 2015, STARSCHEMA LTD.
 * All rights reserved.

 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:

 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.

 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * This class implements the java.sql.Statement interface
 */

package net.starschema.clouddb.jdbc;

import com.google.api.services.bigquery.model.Job;
import com.google.api.services.bigquery.model.QueryResponse;

import java.io.IOException;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * This class implements java.sql.Statement
 *
 * @author Horváth Attila
 * @author Balazs Gunics
 *
 */
public class BQStatement extends BQStatementRoot implements java.sql.Statement {

    public static final int MAX_IO_FAILURE_RETRIES = 3;
    private Job job;

    /**
     * Constructor for BQStatement object just initializes local variables
     *
     * @param projectid
     * @param bqConnection
     */
    public BQStatement(String projectid, BQConnection bqConnection) {
        logger.debug("Constructor of BQStatement is running projectid is: " + projectid);
        this.ProjectId = projectid;
        this.connection = bqConnection;
        this.resultSetType = ResultSet.TYPE_FORWARD_ONLY;
        this.resultSetConcurrency = ResultSet.CONCUR_READ_ONLY;
    }

    /**
     * Constructor for BQStatement object just initializes local variables
     *
     * @param projectid
     * @param bqConnection
     * @param resultSetType
     * @param resultSetConcurrency
     * @throws BQSQLException
     */
    public BQStatement(String projectid, BQConnection bqConnection,
                       int resultSetType, int resultSetConcurrency) throws BQSQLException {
        logger.debug("Constructor of BQStatement is running projectid is: " + projectid +
                ",resultSetType is: " + resultSetType +
                ",resutSetConcurrency is: " + resultSetConcurrency);
        if (resultSetConcurrency == ResultSet.CONCUR_UPDATABLE) {
            throw new BQSQLException(
                    "The Resultset Concurrency can't be ResultSet.CONCUR_UPDATABLE");
        }

        this.ProjectId = projectid;
        this.connection = bqConnection;
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;

    }

    /** {@inheritDoc} */
    @Override
    public ResultSet executeQuery(String querySql) throws SQLException {
        try {
            this.connection.addRunningStatement(this);
            return executeQueryHelper(querySql, false);
        }
        finally {
            this.job = null;
            this.connection.removeRunningStatement(this);
        }
    }

    @Override
    public ResultSet executeQuery(String querySql, boolean unlimitedBillingBytes) throws SQLException {
        try {
            this.connection.addRunningStatement(this);
            return executeQueryHelper(querySql, unlimitedBillingBytes);
        }
        finally {
            this.job = null;
            this.connection.removeRunningStatement(this);
        }
    }

    private ResultSet executeQueryHelper(String querySql, boolean unlimitedBillingBytes) throws SQLException {
        if (this.isClosed()) {
            throw new BQSQLException("This Statement is Closed");
        }

        if (this.job != null) {
            throw new BQSQLException("Already running job");
        }

        this.starttime = System.currentTimeMillis();
        Job referencedJob;
        int retries = 0;
        boolean jobAlreadyCompleted = false;
        // ANTLR Parsing
        BQQueryParser parser = new BQQueryParser(querySql, this.connection);
        querySql = parser.parse();
        try {
            if (this.connection.shouldUseQueryApi()) {
                QueryResponse qr = BQSupportFuncts.runSyncQuery(
                        this.connection.getBigquery(),
                        this.ProjectId,
                        querySql,
                        connection.getDataSet(),
                        this.connection.getUseLegacySql(),
                        this.connection.getMaxBillingBytes(),
                        (long) querytimeout * 1000,
                        (long) getMaxRows()
                );
                boolean fetchedAll = qr.getTotalRows().equals(BigInteger.ZERO) ||
                        qr.getTotalRows().equals(BigInteger.valueOf(qr.getRows().size()));
                // Don't look up the job if we have nothing else we need to do
                referencedJob = fetchedAll ?
                        null :
                        this.connection.getBigquery().jobs().get(this.ProjectId, qr.getJobReference().getJobId()).execute();
                if (qr.getJobComplete()) {
                    if (resultSetType != ResultSet.TYPE_SCROLL_INSENSITIVE) {
                        return new BQForwardOnlyResultSet(
                                this.connection.getBigquery(),
                                this.ProjectId.replace("__", ":").replace("_", "."),
                                referencedJob, this, qr.getRows(), fetchedAll, qr.getSchema());
                    } else if (fetchedAll) {
                        // We can only return scrollable result sets here if we have all the rows: otherwise we'll
                        // have to go get more below
                        return new BQScrollableResultSet(qr.getRows(), this, qr.getSchema());
                    }
                    jobAlreadyCompleted = true;
                }
            } else {
                // Run the query async and return a Job that represents the running query
                referencedJob = startQuery(querySql, unlimitedBillingBytes);
            }
        } catch (IOException e) {
            throw new BQSQLException("Something went wrong creating the query: " + querySql, e);
        }

        try {
            do {
                if (this.connection.isClosed()) {
                    throw new BQSQLException("Connection is closed");
                }

                String status;
                if (jobAlreadyCompleted) {
                    status = "DONE";
                } else {
                    try {
                        status = BQSupportFuncts.getQueryState(referencedJob,
                                this.connection.getBigquery(),
                                this.ProjectId.replace("__", ":").replace("_", "."));
                    } catch (IOException e) {
                        if (retries++ < MAX_IO_FAILURE_RETRIES) {
                            continue;
                        } else {
                            throw new BQSQLException(
                                    "Something went wrong getting results for the job " + referencedJob.getId() + ", query: " + querySql,
                                    e);
                        }
                    }
                }

                if (status.equals("DONE")) {
                    if (resultSetType == ResultSet.TYPE_SCROLL_INSENSITIVE) {
                        return new BQScrollableResultSet(BQSupportFuncts.getQueryResults(
                                this.connection.getBigquery(),
                                this.ProjectId.replace("__", ":").replace("_", "."),
                                referencedJob), this);
                    } else {
                        return new BQForwardOnlyResultSet(
                                this.connection.getBigquery(),
                                this.ProjectId.replace("__", ":").replace("_", "."),
                                referencedJob, this);
                    }
                }
                // Pause execution for half second before polling job status
                // again, to
                // reduce unnecessary calls to the BigQUery API and lower
                // overall
                // application bandwidth.
                Thread.sleep(500);
                this.logger.debug("slept for 500" + "ms, querytimeout is: "
                        + this.querytimeout + "s");
            }
            while (System.currentTimeMillis() - this.starttime <= (long) this.querytimeout * 1000);
            // it runs for a minimum of 1 time
        } catch (IOException e) {
            throw new BQSQLException(
                    "Something went wrong getting results for the job " + referencedJob.getId() + ", query: " + querySql,
                    e);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        this.cancel();
        throw new BQSQLException(
                "Query run took more than the specified timeout");
    }

    /**
     * Extracted out so that we can patch it in the tests for
     * helping to signal timings.
     */
    public Job startQuery(String querySql, boolean unlimitedBillingBytes) throws IOException {
        Long billingBytes = !unlimitedBillingBytes ? this.connection.getMaxBillingBytes() : null;
        this.job =  BQSupportFuncts.startQuery(
                this.connection.getBigquery(),
                this.ProjectId.replace("__", ":").replace("_", "."),
                querySql,
                this.connection.getDataSet(),
                this.connection.getUseLegacySql(),
                billingBytes
        );
        this.logger.debug("Executing Query: " + querySql);
        return this.job;
    }

    public Job getJob() {
        return this.job;
    }

    @Override
    public void cancel() throws SQLException {
        if (this.job == null) {
            return;
        }

        try {
            BQSupportFuncts.cancelQuery(this.job, this.connection.getBigquery(), this.ProjectId.replace("__", ":").replace("_", "."));
        } catch (IOException e) {
            throw new SQLException("Failed to kill query");
        }
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        throw new BQSQLException("Not implemented.");
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        throw new BQSQLException("Not implemented.");
    }

    @Override
    public void close() throws SQLException {
        this.cancel();
        super.close();
    }

}
