package com.github.grishberg.tests;

import com.android.build.gradle.internal.test.report.ReportType;
import com.android.build.gradle.internal.test.report.TestReport;
import com.android.build.gradle.internal.test.report.TestReportExt;
import com.android.ddmlib.AndroidDebugBridge;
import com.github.grishberg.tests.adb.AdbWrapper;
import com.github.grishberg.tests.commands.CommandExecutionException;
import com.github.grishberg.tests.commands.DeviceRunnerCommandProvider;
import com.github.grishberg.tests.common.RunnerLogger;
import com.github.grishberg.tests.sharding.DefaultDeviceTypeAdapter;
import com.github.grishberg.tests.sharding.DeviceTypeAdapter;
import com.github.grishberg.tests.sharding.ShardArgumentsImpl;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Nullable;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.logging.ConsoleRenderer;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.grishberg.tests.common.FileHelper.cleanFolder;

/**
 * Main task for running instrumental tests.
 */
public class InstrumentationTestTask extends DefaultTask {
    private static final String TAG = InstrumentationTestTask.class.getSimpleName();
    private static final String DEFAULT_FLAVOR = "default_flavor";
    public static final String NAME = "instrumentalTests";
    @Nullable
    private String androidSdkPath;
    private File coverageDir;
    private File resultsDir;
    private File reportsDir;
    private DeviceRunnerCommandProvider commandProvider;
    private InstrumentationArgsProvider instrumentationArgsProvider;
    private InstrumentalExtension instrumentationInfo;
    private CommandsForAnnotationProvider commandsForAnnotationProvider;
    private DeviceCommandsRunnerFactory deviceCommandsRunnerFactory;
    private AdbWrapper adbWrapper;
    private RunnerLogger logger;
    private DeviceTypeAdapter deviceTypeAdapter;
    private ProcessCrashHandler processCrashHandler;

    public InstrumentationTestTask() {
        instrumentationInfo = getProject().getExtensions()
                .findByType(InstrumentalExtension.class);
    }

    void initAfterApply(AdbWrapper adbWrapper,
                        DeviceCommandsRunnerFactory deviceCommandsRunnerFactory,
                        RunnerLogger logger) {
        this.adbWrapper = adbWrapper;
        this.deviceCommandsRunnerFactory = deviceCommandsRunnerFactory;
        this.logger = logger;
    }

    @TaskAction
    public void runTask() throws InterruptedException, IOException, CommandExecutionException {
        logger.i(TAG, "InstrumentationTestTask.runTask");

        androidSdkPath = instrumentationInfo.getAndroidSdkPath();
        init();
        adbWrapper.init(androidSdkPath, logger);
        prepareOutputFolders();
        adbWrapper.waitForAdb();

        Environment environment = new Environment(getResultsDir(),
                getReportsDir(), getCoverageDir());
        DeviceCommandsRunner runner = deviceCommandsRunnerFactory.provideDeviceCommandRunner(commandProvider);

        HashMap<String, String> screenshotRelations = new HashMap<>();
        TestRunnerContext context = new TestRunnerContext(instrumentationInfo,
                environment, screenshotRelations, logger);
        if (processCrashHandler != null) {
            context.setProcessCrashHandler(processCrashHandler);
        }
        boolean success = false;
        try {
            success = runner.runCommands(getDeviceList(), context);
        } finally {
            if (instrumentationInfo.isHtmlReportsEnabled()) {
                generateHtmlReport(success, screenshotRelations);
            }
        }
    }

    /**
     * @return List of available devices.
     */
    public List<ConnectedDeviceWrapper> getDeviceList() {
        return adbWrapper.provideDevices();
    }

    private void prepareOutputFolders() throws IOException {
        cleanFolder(getReportsDir());
        cleanFolder(getResultsDir());
        cleanFolder(getCoverageDir());
    }

    private void generateHtmlReport(boolean success, Map<String, String> screenshotMap) {
        TestReport report = new TestReportExt(ReportType.SINGLE_FLAVOR, getResultsDir(),
                getReportsDir(), screenshotMap);
        report.generateReport();
        if (!success) {
            String reportUrl = (new ConsoleRenderer())
                    .asClickableFileUrl(new File(getReportsDir(), "index.html"));
            String message = String.format("There were failing tests. See the report at: %s",
                    reportUrl);
            throw new GradleException(message);
        }
    }

    private void init() {
        AndroidDebugBridge.initIfNeeded(false);
        if (androidSdkPath == null) {
            logger.i(TAG, "androidSdkPath is empty, get path from env ANDROID_HOME");
            androidSdkPath = System.getenv("ANDROID_HOME");
            logger.i(TAG, "androidSdkPath = {}", androidSdkPath);
        }
        if (instrumentationInfo == null) {
            throw new GradleException("Need to set InstrumentationInfo");
        }
        if (commandsForAnnotationProvider == null) {
            commandsForAnnotationProvider = new DefaultCommandsForAnnotationProvider();

            logger.i(TAG, "Init: commandsForAnnotationProvider is empty, use DefaultCommandsForAnnotationProvider");
        }
        if (instrumentationArgsProvider == null) {
            if (deviceTypeAdapter == null) {
                deviceTypeAdapter = new DefaultDeviceTypeAdapter();
            }
            instrumentationArgsProvider = new DefaultInstrumentationArgsProvider(
                    instrumentationInfo, new ShardArgumentsImpl(adbWrapper, deviceTypeAdapter));
            logger.i(TAG, "init: instrumentationArgsProvider is empty, use DefaultInstrumentationArgsProvider");
        }
        if (commandProvider == null) {
            logger.i(TAG, "command provider is empty, use DefaultCommandProvider");
            commandProvider = new DefaultCommandProvider(getProject().getName(),
                    instrumentationArgsProvider, commandsForAnnotationProvider);
        }
    }

    @Input
    public void setInstrumentationInfo(InstrumentalExtension instrumentationInfo) {
        this.instrumentationInfo = instrumentationInfo;
    }

    @Input
    public void setInstrumentationArgsProvider(InstrumentationArgsProvider argsProvider) {
        this.instrumentationArgsProvider = argsProvider;
    }

    @Input
    public void setCommandsForAnnotationProvider(CommandsForAnnotationProvider commandsProvider) {
        this.commandsForAnnotationProvider = commandsProvider;
    }

    @Input
    public void setCommandProvider(DeviceRunnerCommandProvider commandProvider) {
        this.commandProvider = commandProvider;
    }

    @Input
    public void setCoverageDir(File coverageDir) {
        this.coverageDir = coverageDir;
    }

    @Input
    public void setResultsDir(File resultsDir) {
        this.resultsDir = resultsDir;
    }

    @Input
    public void setReportsDir(File reportsDir) {
        this.reportsDir = reportsDir;
    }

    /**
     * Sets shard device type adapter for DefaultInstrumentationArgsProvider.
     * If you use your own implementation of InstrumentationArgsProvider,
     * then write your own shard arguments generation logic.
     */
    @Input
    public void setDeviceTypeAdapter(DeviceTypeAdapter deviceTypeAdapter) {
        this.deviceTypeAdapter = deviceTypeAdapter;
    }

    public File getCoverageDir() {
        if (coverageDir == null) {
            String flavor = instrumentationInfo.getFlavorName() != null ?
                    instrumentationInfo.getFlavorName() : DEFAULT_FLAVOR;
            coverageDir = new File(getProject().getBuildDir(),
                    String.format("outputs/androidTest/coverage/%s", flavor));
            logger.d(TAG, "Coverage dir is empty, generate default value {}", coverageDir);
        }
        return coverageDir;
    }

    public File getResultsDir() {
        if (resultsDir == null) {
            String flavor = instrumentationInfo.getFlavorName() != null ?
                    instrumentationInfo.getFlavorName() : DEFAULT_FLAVOR;
            resultsDir = new File(getProject().getBuildDir(),
                    String.format("outputs/androidTest/%s", flavor));
            logger.d(TAG, "Results dir is empty, generate default value {}", resultsDir);
        }
        return resultsDir;
    }

    @OutputDirectory
    public File getReportsDir() {
        if (reportsDir == null) {
            String flavor = instrumentationInfo.getFlavorName() != null ?
                    instrumentationInfo.getFlavorName() : DEFAULT_FLAVOR;
            reportsDir = new File(getProject().getBuildDir(),
                    String.format("outputs/reports/androidTest/%s", flavor));
            logger.d(TAG, "Reports dir is empty, generate default value {}", reportsDir);
        }
        return reportsDir;
    }

    public void setRunnerLogger(RunnerLogger logger) {
        this.logger = logger;
    }

    @Input
    public void setProcessCrashHandler(ProcessCrashHandler handler) {
        processCrashHandler = handler;
    }
}
