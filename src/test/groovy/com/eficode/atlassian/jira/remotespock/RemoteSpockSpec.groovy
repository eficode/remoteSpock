package com.eficode.atlassian.jira.remotespock

import com.eficode.atlassian.jira.remotespock.beans.responses.SpockOutputType
import com.eficode.atlassian.jira.remotespock.jiraLocalScripts.JiraLocalFailedAndSuccessfulSpockTest
import com.eficode.atlassian.jiraInstanceManager.JiraInstanceManagerRest
import com.eficode.atlassian.jiraInstanceManager.beans.MarketplaceApp
import com.eficode.devstack.deployment.impl.JsmDevDeployment
import com.eficode.devstack.deployment.impl.JsmH2Deployment
import de.gesellix.docker.remote.api.ContainerState
import kong.unirest.core.Cookies
import kong.unirest.core.Unirest
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


    @Shared
    String projectRootPath = new File(PROJECT_ROOT_PATH).canonicalPath
    @Shared
    File groovySrcDir = new File(projectRootPath + "/src/main/groovy/").canonicalFile
    @Shared
    File groovyTestDir = new File(projectRootPath + "/src/test/groovy/").canonicalFile

    @Shared
    JsmDevDeployment jsmDevDep

    def setupSpec() {

        assert groovySrcDir.isDirectory()
        assert groovyTestDir.isDirectory()

        jsmDevDep = new JsmDevDeployment.Builder(baseUrl, jsmLicense, [groovySrcDir.canonicalPath, groovyTestDir.canonicalPath])
                .setJsmJvmDebugPort("5005")
                .setJsmVersion("latest")
                .enableJsmDood()
                .addAppToInstall(MarketplaceApp.getScriptRunnerVersion(baseSrVersion).getDownloadUrl(), srLicense)
                .addAppToInstall("https://marketplace.atlassian.com/download/apps/1211542/version/302030")
                .build()


        Unirest.config().defaultBaseUrl(baseUrl).setDefaultBasicAuth(restAdmin, restPw)


        if (!(reuseContainer && jsmDep?.jsmContainer?.status() == ContainerState.Status.Running)) {

            //Stop and remove if already existing
            jsmDevDep.stopAndRemoveDeployment()

            //Start and wait for the deployment
            jsmDevDep.setupDeployment()
            jsmDep.jiraRest.waitForJiraToBeResponsive()


        }

        sudoCookies = getJiraInstanceManagerRest().acquireWebSudoCookies()
        assert sudoCookies && jsmDep.jsmContainer.status() == ContainerState.Status.Running
    }

    JsmH2Deployment getJsmDep() {
        return this.jsmDevDep.jsmDeployment
    }

    JiraInstanceManagerRest getJiraInstanceManagerRest() {
        return getJsmDep().jiraRest
    }


    public static String getPROJECT_ROOT_PATH() {

        if (System.getenv("flex_projectRootPath")) {
            return System.getenv("flex_projectRootPath")
        }

        String root = ""
        File currentDir = new File(".").canonicalFile
        while (root == "") {
            if (currentDir.listFiles().any { it.name == "pom.xml" }) {
                return currentDir
            } else if (currentDir.canonicalPath == "/") {
                throw new InputMismatchException("Could not find project root")
            } else {
                currentDir = currentDir.parentFile
            }
        }
        throw new InputMismatchException("Could not find project root")

    }


    /** --- SPOCK Test Actions --- **/


    def "Test runSpockTest basics"(String srVersionNumber, boolean last) {

        setup:
        log.info("Testing RunSpockTest")
        JiraInstanceManagerRest jim = getJiraInstanceManagerRest()
        String endpointFilePath = "com/eficode/atlassian/jira/remotespock/remoteSpockEndpoint.groovy"

        assert jim.installScriptRunner(srLicense, srVersionNumber): "Error installing SR version:" + srVersionNumber
        log.info("\tUsing SR version:" + srVersionNumber)


        if (!jim.isSpockEndpointDeployed(true)) {
            log.info("\tRemoteSpock-endpoint isn't already setup, creating it now")
            jim.createScriptedRestEndpoint(endpointFilePath)
        }

        expect:
        assert jim.isSpockEndpointDeployed(true): "isSpockEndpointDeployed() Reports RemoteSpock as NOT deployed even though it should be"
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

    def "Test runSpockTest different outputs"(String srVersionNumber, boolean last) {

        setup:
        log.info("Testing RunSpockTest")
        JiraInstanceManagerRest jim = getJiraInstanceManagerRest()
        String endpointFilePath = "com/eficode/atlassian/jira/remotespock/remoteSpockEndpoint.groovy"
        String reportOutDir = "/var/atlassian/application-data/jira/spockReports"

        assert jim.installScriptRunner(srLicense, srVersionNumber): "Error installing SR version:" + srVersionNumber
        log.info("\tUsing SR version:" + srVersionNumber)


        if (!jim.isSpockEndpointDeployed(true)) {
            log.info("\tRemoteSpock-endpoint isn't already setup, creating it now")
            assert jim.createScriptedRestEndpoint(endpointFilePath): "Error setting up remoteSpock endpoint"
        }

        jsmDep.jsmContainer.runBashCommandInContainer("rm -rf \"$reportOutDir\"")
        jim.clearCodeCaches()

        /** ---- ---- Testing StringSummary ---- ---- **/


        when: "When running a test with one failing and one successful sub-test with StringSummary output"
        Map spockClassOut = jim.runSpockTest(JiraLocalFailedAndSuccessfulSpockTest.canonicalName, "", SpockOutputType.StringSummary)

        then: "Output should confirm 2 tests, one failed and one successful"
        spockClassOut.toString().contains(" 1 tests successful")
        spockClassOut.toString().contains(" 1 tests successful")

        when: "When running a test with an output dir"

        spockClassOut = jim.runSpockTest(JiraLocalFailedAndSuccessfulSpockTest.canonicalName, "", SpockOutputType.StringSummary, reportOutDir)
        String lsOut = jsmDep.jsmContainer.runBashCommandInContainer("ls -l \"$reportOutDir\"").find { true }

        then: "The data returned should still be the same, but files should be stored on the FS"
        spockClassOut.toString().contains(" 1 tests successful")
        spockClassOut.toString().contains(" 1 tests successful")
        spockClassOut.keySet().every() {lsOut.contains(it.toString())}
        spockClassOut.every {fileName, body ->
            jsmDep.jsmContainer.runBashCommandInContainer("cat $reportOutDir/$fileName").first().trim() == body.toString().trim()
        }



        /** ---- ---- Testing LegacyXml ---- ---- **/


        when: "When running a test with one failing and one successful sub-test with LegacyXml output"
        spockClassOut = jim.runSpockTest(JiraLocalFailedAndSuccessfulSpockTest.canonicalName, "", SpockOutputType.LegacyXml)

        then: "Output should confirm 2 tests, one failed"
        spockClassOut.size() == 1
        spockClassOut.toString().count("tests=\"2\" skipped=\"0\" failures=\"1\" errors=\"0\"") == 1


        when: "When running a test with an output dir"

        spockClassOut = jim.runSpockTest(JiraLocalFailedAndSuccessfulSpockTest.canonicalName, "", SpockOutputType.LegacyXml, reportOutDir)
        lsOut = jsmDep.jsmContainer.runBashCommandInContainer("ls -l \"$reportOutDir\"").find { true }

        then: "The data returned should still be the same, but files should be stored on the FS"
        spockClassOut.size() == 1
        spockClassOut.toString().count("tests=\"2\" skipped=\"0\" failures=\"1\" errors=\"0\"") == 1
        spockClassOut.keySet().every {lsOut.contains(it.toString())}
        spockClassOut.every {fileName, body ->
            jsmDep.jsmContainer.runBashCommandInContainer("cat $reportOutDir/$fileName").first().trim() == body.toString().trim()
        }


        /** ---- ---- Testing OpenTestReport ---- ---- **/

        when: "When running a test with one failing and one successful sub-test with OpenTestReport output"
        spockClassOut = jim.runSpockTest(JiraLocalFailedAndSuccessfulSpockTest.canonicalName, "", SpockOutputType.OpenTestReport)

        then: "Output should confirm 2 tests, one failed"
        spockClassOut.size() == 3
        spockClassOut.toString().count("result status=\"FAILED\"") == 2
        spockClassOut.toString().count("result status=\"SUCCESSFUL\"") == 6

        when: "When running a test with an output dir"

        spockClassOut = jim.runSpockTest(JiraLocalFailedAndSuccessfulSpockTest.canonicalName, "", SpockOutputType.OpenTestReport, reportOutDir)
        lsOut = jsmDep.jsmContainer.runBashCommandInContainer("ls -l \"$reportOutDir\"").find { true }

        then: "The data returned should still be the same, but files should be stored on the FS"
        spockClassOut.size() == 3
        spockClassOut.toString().count("result status=\"FAILED\"") == 2
        spockClassOut.toString().count("result status=\"SUCCESSFUL\"") == 6
        spockClassOut.keySet().every {lsOut.contains(it.toString())}
        spockClassOut.every {fileName, body ->
            jsmDep.jsmContainer.runBashCommandInContainer("cat $reportOutDir/$fileName").first().trim() == body.toString().trim()
        }



        /** ---- ---- Testing AllureReport ---- ---- **/

        when: "When running a test with one failing and one successful sub-test with AllureReport output"
        spockClassOut = jim.runSpockTest(JiraLocalFailedAndSuccessfulSpockTest.canonicalName, "", SpockOutputType.AllureReport)

        then: "Output should confirm 2 tests, one failed"
        spockClassOut.size() == 5
        spockClassOut.toString().count("\"status\":\"passed\"") == 1
        spockClassOut.toString().count("\"status\":\"failed\"") == 1


        when: "When running a test with an output dir"

        spockClassOut = jim.runSpockTest(JiraLocalFailedAndSuccessfulSpockTest.canonicalName, "", SpockOutputType.AllureReport, reportOutDir)
        lsOut = jsmDep.jsmContainer.runBashCommandInContainer("ls -l \"$reportOutDir\"").find { true }

        then: "The data returned should still be the same, but files should be stored on the FS"
        spockClassOut.size() == 5
        spockClassOut.toString().count("\"status\":\"passed\"") == 1
        spockClassOut.toString().count("\"status\":\"failed\"") == 1
        spockClassOut.keySet().every {lsOut.contains(it.toString())}
        spockClassOut.every {fileName, body ->
            jsmDep.jsmContainer.runBashCommandInContainer("cat $reportOutDir/$fileName").first().trim() == body.toString().trim()
        }



        cleanup:

        if (last) {
            assert jim.installScriptRunner(srLicense, baseSrVersion): "Error installing SR version:" + baseSrVersion
        }

        where:
        srVersionNumber | last
        "latest"        | false

    }


}
