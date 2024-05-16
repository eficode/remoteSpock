package com.eficode.atlassian.jira.remotespock

import com.eficode.atlassian.jiraInstanceManager.JiraInstanceManagerRest
import com.eficode.atlassian.jiraInstanceManager.beans.*
import com.eficode.devstack.deployment.impl.JsmH2Deployment
import de.gesellix.docker.remote.api.ContainerState
import groovy.io.FileType
import kong.unirest.core.Cookies
import kong.unirest.core.HttpResponse
import kong.unirest.core.Unirest
import kong.unirest.core.UnirestInstance
import org.apache.groovy.json.internal.LazyMap
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class RemoteSpockSpec extends Specification {

    @Shared
    static Logger log = LoggerFactory.getLogger(RemoteSpockSpec.class)

    @Shared
    static String baseUrl = "http://jira.localhost:8080"

    @Shared
    static String baseSrVersion = "latest"

    @Shared
    static JsmH2Deployment jsmDep = new JsmH2Deployment(baseUrl)

    @Shared
    static boolean reuseContainer = true //If true and container is already setup, it will be re-used.

    @Shared
    static String jsmLicense = new File(System.getProperty("user.home") + "/.licenses/jira/jsm.license").text
    @Shared
    static String srLicense = new File(System.getProperty("user.home") + "/.licenses/jira/sr.license").text


    @Shared
    static String restAdmin = "admin"

    @Shared
    static String restPw = "admin"

    @Shared
    static Cookies sudoCookies

    def setupSpec() {

        Unirest.config().defaultBaseUrl(baseUrl).setDefaultBasicAuth(restAdmin, restPw)


        jsmDep.setJiraLicense(jsmLicense)
        jsmDep.appsToInstall.put(MarketplaceApp.getScriptRunnerVersion(baseSrVersion).getDownloadUrl(), srLicense)
        if (!jsmDep.jsmContainer.created) {
            jsmDep.jsmContainer.enableJvmDebug()
        }

        jsmDep.jsmContainer.enableAppUpload()

        if (!(reuseContainer && jsmDep?.jsmContainer?.status() == ContainerState.Status.Running)) {

            //Stop and remove if already existing
            jsmDep.stopAndRemoveDeployment()

            //Start and wait for the deployment
            jsmDep.setupDeployment()
            jsmDep.jiraRest.waitForJiraToBeResponsive()

            assert jiraInstanceManagerRest.installScriptRunner(srLicense, baseSrVersion): "Error installing SR version:" + baseSrVersion

        }

        sudoCookies = new JiraInstanceManagerRest(restAdmin, restPw, baseUrl).acquireWebSudoCookies()
        assert sudoCookies && jsmDep.jsmContainer.status() == ContainerState.Status.Running
    }

    JiraInstanceManagerRest getJiraInstanceManagerRest() {
        return getJsmDep().jiraRest
    }



    /** --- SPOCK Test Actions --- **/


    def "Test runSpockTest"(String srVersionNumber, boolean last) {

        setup:
        log.info("Testing RunSpockTest")
        JiraInstanceManagerRest jim = getJiraInstanceManagerRest()
        String endpointFilePath = "com/eficode/atlassian/jira/remotespock/remoteSpockEndpoint.groovy"

        assert jim.installScriptRunner(srLicense, srVersionNumber): "Error installing SR version:" + srVersionNumber
        log.info("\tUsing SR version:" + srVersionNumber)



        if (jim.isSpockEndpointDeployed(true)) {
            log.info("\tRemoteSpock-endpoint is already deployed, removing it before test")
            jim.deleteScriptedRestEndpointId(jim.getScriptedRestEndpointId("remoteSpock"))
        }
        if (jim.deleteScriptrunnerFile(endpointFilePath)) {
            log.info("\tDeleted RemoteSpock-endpoint script from JIRA")
        }

        expect:
        assert !jim.isSpockEndpointDeployed(true) : "isSpockEndpointDeployed() Reports RemoteSpock as deployed even though it isn't"
        assert jim.deploySpockEndpoint(["com.riadalabs.jira.plugins.insight"]) : "Error deploying SpockRemote endpoint"
        assert jim.isSpockEndpointDeployed(true) : "isSpockEndpointDeployed() Reports RemoteSpock as NOT deployed even though it should be"
        assert jim.getScriptrunnerFile(endpointFilePath).readLines().any {it.startsWith("@WithPlugin(\"com.riadalabs.jira.plugins.insight\")")} : "The deployed endpoint does not appear to have the specified @WithPlugin statement"
        log.info("\tSuccessfully deployed endpoint")




        when: "When running the main test as packageToRun, classToRun and methodToRun"
        File jiraLocalScriptsDir = new File("src/test/groovy/com/eficode/atlassian/jira/remotespock/jiraLocalScripts")
        assert jiraLocalScriptsDir.isDirectory()
        File jiraLocalScriptRootDir = new File("src/test/groovy")
        assert jiraLocalScriptRootDir.isDirectory()


        log.info("Uploading main package test class")
        assert jim.updateScriptrunnerFile(new File("src/test/groovy/com/eficode/atlassian/jira/remotespock/jiraLocalScripts/JiraLocalSpockTest.groovy"), "com/eficode/atlassian/jira/remotespock/jiraLocalScripts/JiraLocalSpockTest.groovy"): "Error updating main spock package file"
        sleep(1500)//Wait for sr to detect file changes
        log.info("\tRunning matching package, class and method tests")
        String spockClassOut = jim.runSpockTest("com.eficode.atlassian.jira.remotespock.jiraLocalScripts.JiraLocalSpockTest")
        String spockMethodOut = jim.runSpockTest("com.eficode.atlassian.jira.remotespock.jiraLocalScripts.JiraLocalSpockTest", "A successful test in JiraLocalSpockTest")


        then:
        spockClassOut.contains(" 1 tests successful")
        spockMethodOut.contains(" 1 tests successful")



        cleanup:

        if (last) {
            assert jim.installScriptRunner(srLicense, baseSrVersion): "Error installing SR version:" + baseSrVersion
        }

        where:
        srVersionNumber | last
        "latest"        | false

    }


}
