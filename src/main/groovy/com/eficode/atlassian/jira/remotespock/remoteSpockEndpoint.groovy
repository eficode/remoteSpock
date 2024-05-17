package com.eficode.atlassian.jira.remotespock

import com.eficode.atlassian.jira.remotespock.beans.responses.SpockOutputType


/*
To Add Xray Reporting:

import app.getxray.xray.junit.customjunitxml.EnhancedLegacyXmlReportGeneratingListener

@Grapes(
        @Grab(group = 'app.getxray', module = 'xray-junit-extensions', version = '0.8.0')
)

EnhancedLegacyXmlReportGeneratingListener xrayListener = new EnhancedLegacyXmlReportGeneratingListener(outputDir.toPath(), printWriter)

launcher.registerTestExecutionListeners(xrayListener)
xrayListener.executionFinished(testPlan)
*/


@Grapes(
        @Grab(group = 'io.qameta.allure', module = 'allure-junit5', version = '2.27.0')
)

/**
 * For inspiration and inspiration for further imrpovements:
 *
 * https://stackoverflow.com/questions/39111501/whats-the-equivalent-of-org-junit-runner-junitcore-runclasses-in-junit-5
 * https://gist.github.com/danhyun/972c21395f11cde0759565991b08d513
 * https://stackoverflow.com/questions/9062412/generate-xml-files-used-by-junit-reports
 * https://junit.org/junit5/docs/snapshot/user-guide/#junit-platform-reporting
 */


import com.onresolve.scriptrunner.runner.customisers.WithPlugin
import groovy.transform.Field
import io.qameta.allure.junitplatform.AllureJunitPlatform
import org.apache.commons.io.filefilter.AgeFileFilter
import org.junit.platform.launcher.TestPlan
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.transform.BaseScript
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator
import org.junit.platform.launcher.Launcher
import org.junit.platform.launcher.LauncherDiscoveryRequest
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import org.junit.platform.launcher.listeners.TestExecutionSummary
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener
import org.junit.platform.reporting.open.xml.OpenTestReportGeneratingListener
import org.spockframework.runtime.model.FeatureMetadata
import org.junit.platform.launcher.listeners.MutableTestExecutionSummary

import javax.servlet.http.HttpServletRequest
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response
import org.codehaus.jackson.map.ObjectMapper
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.LogManager

import java.lang.reflect.Method
import java.nio.file.Path

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

@BaseScript CustomEndpointDelegate delegate
//@WithPlugin("com.riadalabs.jira.plugins.insight")



Logger log = LogManager.getLogger("remoteSpec.util.jiraLocal.remoteSpocEndpoint") as Logger
Configurator.setLevel(log, Level.ALL)
ObjectMapper objectMapper = new ObjectMapper()

remoteSpock(httpMethod: "POST", groups: ["jira-administrators"]) { MultivaluedMap queryParams, String body, HttpServletRequest request ->


    log.info("Remote Spock triggered")
    String urlSubPath = getAdditionalPath(request)
    log.debug("\tGot url sub path:" + urlSubPath)
    log.debug("\tGot query parameters:" + queryParams)
    log.debug("\tGot body:" + body.take(150) + (body.size() > 150 ? "..." : ""))


    Map finalOutput = [:]
    if (urlSubPath.startsWith("/spock/class")) {
        log.info("\tRunning spock class")

        Map bodyMap = objectMapper.readValue(body, Map)

        finalOutput = runSpockClass(log, bodyMap.get("className", null) as String, bodyMap.get("methodName", null) as String, bodyMap.get("outputType", null) as SpockOutputType, bodyMap.get("outputDirPath", null) as String)

        log.info("Finished running spock class, returning output:\n" + finalOutput)
        return Response.ok(finalOutput, MediaType.APPLICATION_JSON).build()

    } else {
        return Response.status(Response.Status.BAD_REQUEST).entity("Unsupported url sub path:" + urlSubPath).build()
    }


}

static Map runSpockClass(Logger logEndpoint, String spockClassName, String spockMethodName = "", SpockOutputType outputType = null, String outputDirPath = "") {


    logEndpoint.info("Starting Spock test")
    String loggerName = "RemoteSpock-ScriptLog" + System.currentTimeMillis().toString().takeRight(4)
    logEndpoint.debug("\tScript will use logger named:" + loggerName)

    logEndpoint.debug("\tRetrieving Spock class:" + spockClassName)

    Class spockClass = Class.forName(spockClassName)
    logEndpoint.debug("\t\tFound class: " + spockClass.canonicalName)
    Method spockMethod = null

    if (spockMethodName) {
        logEndpoint.debug("\tRetrieving Spock method:" + spockMethodName)
        spockMethod = spockClass.getDeclaredMethods().find {
            FeatureMetadata featureMetadata = it.getAnnotation(FeatureMetadata)
            if (!featureMetadata || featureMetadata.name() != spockMethodName) {
                return false
            }
            return true
        }

        if (!spockMethod) {
            throw new InputMismatchException("Could not find method: ${spockClass.canonicalName}#$spockMethodName")
        }
        logEndpoint.debug("\t\tFound method: " + spockMethod.name + " (compiled name)")
    }

    File outputDir = outputDirPath ? new File(outputDirPath) : null



    Map spockOut = executeSpockTest(logEndpoint, spockClass, spockMethod, outputType, outputDir)


    return spockOut

}


static Map executeSpockTest(Logger log, Class aClass, Method aMethod = null, SpockOutputType outputType = SpockOutputType.StringSummary, File spockOutDir = null) {


    LauncherDiscoveryRequest request
    //Clear any previous related properties
    System.clearProperty("junit.platform.reporting.open.xml.enabled")
    System.clearProperty("junit.platform.reporting.output.dir")
    System.clearProperty("allure.results.directory")





    if (aMethod) {
        request = LauncherDiscoveryRequestBuilder.request().selectors(selectMethod(aClass, aMethod)).build()
    } else {
        request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(aClass)).build()
    }


    Launcher launcher = LauncherFactory.create();

    Date startOfTest = new Date()
    Map outputMap = [:]
    File outputDir = spockOutDir
    if (!spockOutDir) {
        outputDir = File.createTempDir("spockTemp")
        log.info("Created temp dir:" + outputDir.name + ", for test:" + outputType.name())
    }
    outputDir.mkdirs()


    switch (outputType) {

        case SpockOutputType.StringSummary:
            SummaryGeneratingListener sumListener = new SummaryGeneratingListener()
            launcher.registerTestExecutionListeners(sumListener)
            launcher.execute(request)
            TestExecutionSummary summary = sumListener.getSummary()
            StringWriter stringWriter = new StringWriter()
            PrintWriter printWriter = new PrintWriter(stringWriter)
            summary.printTo(printWriter)
            summary.printFailuresTo(printWriter)

            String output = stringWriter.buffer.toString()
            outputMap = ["summary": output]

            break

        case SpockOutputType.LegacyXml:

            StringWriter stringWriter = new StringWriter()
            PrintWriter printWriter = new PrintWriter(stringWriter)
            LegacyXmlReportGeneratingListener listener = new LegacyXmlReportGeneratingListener(outputDir.toPath(), printWriter)
            launcher.registerTestExecutionListeners(listener)
            TestPlan testPlan = launcher.discover(request)
            listener.testPlanExecutionStarted(testPlan)
            launcher.execute(request)
            listener.testPlanExecutionFinished(testPlan)


            if (stringWriter.buffer.length()) {
                //Should only happen if exception is thrown
                outputMap.put("exception", stringWriter.buffer.toString())
            }


            outputDir.listFiles(new AgeFileFilter(startOfTest, false) as FileFilter).findAll { it.name.endsWith(".xml") }.each {
                outputMap.put(it.name, it.text)
            }


            break

        case SpockOutputType.OpenTestReport:

            System.setProperty("junit.platform.reporting.open.xml.enabled", "true")
            System.setProperty("junit.platform.reporting.output.dir", outputDir.canonicalPath)


            OpenTestReportGeneratingListener listener = new OpenTestReportGeneratingListener()
            launcher.registerTestExecutionListeners(listener)
            TestPlan testPlan = launcher.discover(request)
            listener.testPlanExecutionStarted(testPlan)
            launcher.execute(request)
            listener.testPlanExecutionFinished(testPlan)


            outputDir.listFiles().findAll { it.name.endsWith(".xml") && it.text != null }.each {
                outputMap.put(it.name, it.text)
            }


            break

        case SpockOutputType.AllureReport:

            System.setProperty("allure.results.directory", outputDir.canonicalPath)
            def direProp = System.getProperty("allure.results.directory")


            AllureJunitPlatform listener = new AllureJunitPlatform()
            launcher.registerTestExecutionListeners(listener)
            TestPlan testPlan = launcher.discover(request)
            listener.testPlanExecutionStarted(testPlan)
            launcher.execute(request)
            listener.testPlanExecutionFinished(testPlan)


            //listener.lifecycle.getDefaultWriter()

            outputDir.listFiles().findAll { it.name.endsWith(".json") && it.text != null }.each {
                outputMap.put(it.name, it.text)
            }


            break
        default:
            throw new InputMismatchException("Unsupported output type ${outputType}")


    }


    //Cleanup temp dirs
    File tempRoot = new File(System.getProperty("java.io.tmpdir"))
    tempRoot.eachDir{
        if (it.name.startsWith("spockTemp")) {
            assert it.deleteDir()
            log.info("\tDeleted temp dir:" + it.name)
        }
    }



    System.clearProperty("junit.platform.reporting.open.xml.enabled")
    System.clearProperty("junit.platform.reporting.output.dir")
    System.clearProperty("allure.results.directory")


    return outputMap


}



