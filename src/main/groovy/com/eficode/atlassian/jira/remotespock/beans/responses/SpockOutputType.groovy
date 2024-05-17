package com.eficode.atlassian.jira.remotespock.beans.responses

/**
 * Used to determine what report type is wanted when running spock tests
 */
enum SpockOutputType {

    StringSummary,
    LegacyXml,
    OpenTestReport,
    AllureReport



}