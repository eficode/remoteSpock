package com.eficode.atlassian.jira.remotespock.jiraLocalScripts


import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class JiraLocalFailedAndSuccessfulSpockTest extends Specification {

    @Shared
    static Logger log = LoggerFactory.getLogger(JiraLocalFailedAndSuccessfulSpockTest.class)


    def "A successful test in JiraLocalSpockTest"() {

        setup:

        log.warn("Running spock test:" + this.specificationContext.currentIteration.name)
        expect:
        true

        cleanup:
        log.warn("\tTest finished with exception:" + $spock_feature_throwable)

    }

    def "A failed test in JiraLocalSpockTest"() {

        setup:

        log.warn("Running spock test:" + this.specificationContext.currentIteration.name)
        expect:
        false

        cleanup:
        log.warn("\tTest finished with exception:" + $spock_feature_throwable)

    }



}
