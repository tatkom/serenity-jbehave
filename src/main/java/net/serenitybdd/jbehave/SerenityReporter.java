package net.serenitybdd.jbehave;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import net.serenitybdd.core.Serenity;
import net.serenitybdd.core.SerenityListeners;
import net.serenitybdd.core.SerenityReports;
import net.thucydides.core.model.*;
import net.thucydides.core.model.stacktrace.RootCauseAnalyzer;
import net.thucydides.core.reports.ReportService;
import net.thucydides.core.steps.BaseStepListener;
import net.thucydides.core.steps.ExecutedStepDescription;
import net.thucydides.core.steps.StepEventBus;
import net.thucydides.core.steps.StepFailure;
import net.thucydides.core.util.Inflector;
import net.thucydides.core.util.NameConverter;
import net.thucydides.core.webdriver.Configuration;
import net.thucydides.core.webdriver.ThucydidesWebDriverSupport;
import org.codehaus.plexus.util.StringUtils;
import org.jbehave.core.configuration.Keywords;
import org.jbehave.core.model.*;
import org.jbehave.core.model.Story;
import org.jbehave.core.reporters.StoryReporter;
import org.junit.internal.AssumptionViolatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static net.thucydides.core.ThucydidesSystemProperty.WEBDRIVER_DRIVER;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;

public class SerenityReporter implements StoryReporter {

    private static final Logger logger = LoggerFactory.getLogger(SerenityReporter.class);

    private ThreadLocal<SerenityListeners> serenityListenersThreadLocal;
    private ThreadLocal<ReportService> reportServiceThreadLocal;
    private final List<BaseStepListener> baseStepListeners;

    private final Configuration systemConfiguration;
    private static final String OPEN_PARAM_CHAR = "\uff5f";
    private static final String CLOSE_PARAM_CHAR = "\uff60";

    private static final String PENDING = "pending";
    private static final String MANUAL = "manual";
    private static final String SKIP = "skip";
    private static final String WIP = "wip";
    private static final String IGNORE = "ignore";
    private static final String BEFORE_STORIES = "BeforeStories";
    private static final String AFTER_STORIES = "AfterStories";

    private GivenStoryMonitor givenStoryMonitor;

    public SerenityReporter(Configuration systemConfiguration) {
        this.systemConfiguration = systemConfiguration;
        serenityListenersThreadLocal = new ThreadLocal<>();
        reportServiceThreadLocal = new ThreadLocal<>();
        baseStepListeners = Lists.newArrayList();
        givenStoryMonitor = new GivenStoryMonitor();
    }


    protected void clearListeners() {
        serenityListenersThreadLocal.remove();
        reportServiceThreadLocal.remove();
        givenStoryMonitor.clear();
    }

    protected SerenityListeners getSerenityListeners() {
        if (serenityListenersThreadLocal.get() == null) {
            SerenityListeners listeners = SerenityReports.setupListeners(systemConfiguration);
            serenityListenersThreadLocal.set(listeners);
            synchronized (baseStepListeners) {
                baseStepListeners.add(listeners.getBaseStepListener());
            }
        }
        return serenityListenersThreadLocal.get();
    }

    protected ReportService getReportService() {
        return SerenityReports.getReportService(systemConfiguration);
    }

    public void storyNotAllowed(Story story, String filter) {
        logger.debug("not allowed story ".concat(story.getName()));
    }

    public void storyCancelled(Story story, StoryDuration storyDuration) {
        logger.debug("cancelled story ".concat(story.getName()));
    }

    private Stack<Story> storyStack = new Stack<>();

    private Stack<String> activeScenarios = new Stack<>();
    private List<String> givenStories = Lists.newArrayList();
    private Map<String, Meta> scenarioMeta = new ConcurrentHashMap<>();
    private Set<String> scenarioMetaProcessed = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private Story currentStory() {
        return storyStack.peek();
    }

    private void currentStoryIs(Story story) {
        storyStack.push(story);
    }

    private Map<String, String> storyMetadata;

    private void clearActiveScenariosData() {
        activeScenarios.clear();
        scenarioMeta.clear();
        scenarioMetaProcessed.clear();
    }

    private void registerScenariosMeta(Story story) {
        final List<Scenario> scenarios = story.getScenarios();
        for (Scenario scenario : scenarios) {
            scenarioMeta.put(scenario.getTitle(), scenario.getMeta());
        }
    }

    public void beforeStory(Story story, boolean givenStory) {
        logger.debug("before story ".concat(story.getName()));
        prepareSerenityListeners();

        currentStoryIs(story);
        noteAnyGivenStoriesFor(story);
        storyMetadata = getMetadataFrom(story.getMeta());
        if (!isFixture(story) && !givenStory) {

            clearActiveScenariosData();
            registerScenariosMeta(story);

            configureDriver(story);

            SerenityStepFactory.resetContext();

            if (!isAStoryLevelGiven(story)) {
                startTestSuiteForStory(story);
                if (givenStoriesPresentFor(story)) {
                    startTestForFirstScenarioIn(story);
                }
            }

        } else if (givenStory) {
            shouldNestScenarios(true);
        }
        registerStoryMeta(story.getMeta());
    }

    private void prepareSerenityListeners() {
        getSerenityListeners().withDriver(ThucydidesWebDriverSupport.getDriver());
    }

    private boolean nestScenarios = false;

    private boolean shouldNestScenarios() {
        return nestScenarios;
    }

    private void shouldNestScenarios(boolean nestScenarios) {
        this.nestScenarios = nestScenarios;
    }

    private void startTestForFirstScenarioIn(Story story) {
        Scenario firstScenario = story.getScenarios().get(0);
        startScenarioCalled(firstScenario.getTitle(), story.getPath() + ";" + firstScenario.getTitle());
        StepEventBus.getEventBus().stepStarted(ExecutedStepDescription.withTitle("Preconditions"));
        shouldNestScenarios(true);
    }

    public void beforeScenario(String scenarioTitle) {
        logger.debug("before scenario started ".concat(scenarioTitle));

        if (shouldResetStepsBeforeEachScenario()) {
            SerenityStepFactory.resetContext();
        }

        resetDriverIfNecessary();

        if (isCurrentScenario(scenarioTitle)) {
            return;
        }

        if (shouldNestScenarios()) {
            startNewStep(scenarioTitle);
        } else {
            startScenarioCalled(scenarioTitle, this.currentStory().getPath() + ";" + scenarioTitle);
            scenarioMeta(scenarioMeta.get(scenarioTitle));
            scenarioMetaProcessed.add(scenarioTitle);
        }
    }

    private void resetDriverIfNecessary() {
        if (Serenity.currentDriverIsDisabled()) {
            Serenity.getWebdriverManager().resetDriver();
        }
    }

    private boolean isCurrentScenario(String scenarioTitle) {
        return !activeScenarios.empty() && scenarioTitle.equals(activeScenarios.peek());
    }

    private String currentScenarioTitle() {
        return (activeScenarios.isEmpty()) ? "" : activeScenarios.peek();
    }

    private Optional<Scenario> currentScenario() {
        for (Scenario scenario : currentStory().getScenarios()) {
            if (scenario.getTitle().equals(currentScenarioTitle())) {
                return Optional.of(scenario);
            }
        }
        return Optional.absent();
    }

    private void startNewStep(String scenarioTitle) {
        if (givenStoryMonitor.isInGivenStory() && StepEventBus.getEventBus().areStepsRunning()) {
            StepEventBus.getEventBus().updateCurrentStepTitleAsPrecondition(scenarioTitle);
        } else {
            StepEventBus.getEventBus().stepStarted(ExecutedStepDescription.withTitle(scenarioTitle),
                    givenStoryMonitor.isInGivenStory());
        }
    }

    private boolean givenStoriesPresentFor(Story story) {
        return !story.getGivenStories().getStories().isEmpty();
    }

    private void startTestSuiteForStory(Story story) {
        String storyName = removeSuffixFrom(story.getName());
        String storyTitle = (isNotEmpty(story.getDescription().asString())) ? story.getDescription().asString() : NameConverter.humanize(storyName);

        net.thucydides.core.model.Story userStory
                = net.thucydides.core.model.Story.withIdAndPath(storyName, storyTitle, story.getPath())
                .withNarrative(getNarrativeFrom(story));
        StepEventBus.getEventBus().testSuiteStarted(userStory);
        registerTags(story);
    }

    private String getNarrativeFrom(Story story) {
        return (!story.getNarrative().isEmpty()) ?
                story.getNarrative().asString(new Keywords()).trim() : "";
    }

    private void noteAnyGivenStoriesFor(Story story) {
        for (GivenStory given : story.getGivenStories().getStories()) {
            String givenStoryName = new File(given.getPath()).getName();
            givenStories.add(givenStoryName);
        }
    }

    private boolean isAStoryLevelGiven(Story story) {
        for (String givenStoryName : givenStories) {
            if (hasSameName(story, givenStoryName)) {
                return true;
            }
        }
        return false;
    }

    private void givenStoryDone(Story story) {
        givenStories.remove(story.getName());
    }

    private boolean hasSameName(Story story, String givenStoryName) {
        return story.getName().equalsIgnoreCase(givenStoryName);
    }

    private void configureDriver(Story story) {
        StepEventBus.getEventBus().setUniqueSession(systemConfiguration.shouldUseAUniqueBrowser());
        String requestedDriver = getRequestedDriver(story.getMeta());
        // An annotated driver that ends with "!" overrides the command-line configured driver
        if (isEmphatic(requestedDriver)) {
            ThucydidesWebDriverSupport.useDefaultDriver(unemphasised(requestedDriver));
        } else if (StringUtils.isNotEmpty(requestedDriver) && (!driverIsProvidedInTheEnvironmentVariables())){
            ThucydidesWebDriverSupport.useDefaultDriver(requestedDriver);
        }
    }

    private String unemphasised(String requestedDriver) {
        return requestedDriver.replace("!","");
    }

    private boolean isEmphatic(String requestedDriver) {
        return requestedDriver != null && requestedDriver.endsWith("!");
    }

    private boolean driverIsProvidedInTheEnvironmentVariables() {
        return (isNotEmpty(systemConfiguration.getEnvironmentVariables().getProperty(WEBDRIVER_DRIVER)));
    }

    private void registerTags(Story story) {
        registerStoryIssues(story.getMeta());
        registerStoryFeaturesAndEpics(story.getMeta());
        registerStoryTags(story.getMeta());
        registerStoryMeta(story.getMeta());
    }

    private boolean isFixture(Story story) {
        return (story.getName().equals(BEFORE_STORIES) || story.getName().equals(AFTER_STORIES));
    }

    private String getRequestedDriver(Meta metaData) {

        if (metaData == null) {
            return null;
        }

        if (StringUtils.isNotEmpty(metaData.getProperty("driver"))) {
            return metaData.getProperty("driver");
        }
        if (systemConfiguration.getDriverType() != null) {
            return systemConfiguration.getDriverType().toString();
        }
        return null;
    }

    private List<String> getIssueOrIssuesPropertyValues(Meta metaData) {
        return getTagPropertyValues(metaData, "issue");
    }

    private List<TestTag> getFeatureOrFeaturesPropertyValues(Meta metaData) {
        List<String> features = getTagPropertyValues(metaData, "feature");

        return features.stream().map(
                featureName -> TestTag.withName(featureName).andType("feature")
        ).collect(Collectors.toList());
    }

    private List<TestTag> getEpicOrEpicsPropertyValues(Meta metaData) {
        List<String> epics = getTagPropertyValues(metaData, "epic");
        return epics.stream().map(
                epicName -> TestTag.withName(epicName).andType("epic")
        ).collect(Collectors.toList());
    }

    private List<TestTag> getTagOrTagsPropertyValues(Meta metaData) {
        List<String> tags = getTagPropertyValues(metaData, "tag");
        return tags.stream()
                .map(  this::toTag )
                .collect(Collectors.toList());
    }

    public TestTag toTag(String tag) {
        List<String> tagParts = Lists.newArrayList(Splitter.on(":").trimResults().split(tag));
        if (tagParts.size() == 2) {
            return TestTag.withName(tagParts.get(1)).andType(tagParts.get(0));
        } else {
            return TestTag.withName("true").andType(tagParts.get(0));
        }
    }


    private List<String> getTagPropertyValues(Meta metaData, String tagType) {
        if (metaData == null) {
            return new ArrayList<>();
        }

        String singularTag = metaData.getProperty(tagType);
        String pluralTagType = Inflector.getInstance().pluralize(tagType);

        String multipleTags = metaData.getProperty(pluralTagType);
        String allTags = Joiner.on(',').skipNulls().join(singularTag, multipleTags);

        return Lists.newArrayList(Splitter.on(',').omitEmptyStrings().trimResults().split(allTags));
    }

    private void registerIssues(Meta metaData) {
        List<String> issues = getIssueOrIssuesPropertyValues(metaData);

        if (!issues.isEmpty()) {
            StepEventBus.getEventBus().addIssuesToCurrentTest(issues);
        }
    }

    private void registerStoryIssues(Meta metaData) {
        List<String> issues = getIssueOrIssuesPropertyValues(metaData);

        if (!issues.isEmpty()) {
            StepEventBus.getEventBus().addIssuesToCurrentStory(issues);
        }
    }

    private void registerFeaturesAndEpics(Meta metaData) {
        List<TestTag> featuresAndEpics = featureAndEpicTags(metaData);

        if (!featuresAndEpics.isEmpty()) {
            StepEventBus.getEventBus().addTagsToCurrentTest(featuresAndEpics);
        }
    }

    private List<TestTag> featureAndEpicTags(Meta metaData) {
        List<TestTag> featuresAndEpics = Lists.newArrayList();
        featuresAndEpics.addAll(getFeatureOrFeaturesPropertyValues(metaData));
        featuresAndEpics.addAll(getEpicOrEpicsPropertyValues(metaData));
        return featuresAndEpics;
    }

    private void registerStoryFeaturesAndEpics(Meta metaData) {
        List<TestTag> featuresAndEpics = featureAndEpicTags(metaData);

        if (!featuresAndEpics.isEmpty()) {
            StepEventBus.getEventBus().addTagsToCurrentStory(featuresAndEpics);
        }
    }

    private void registerTags(Meta metaData) {
        List<TestTag> tags = getTagOrTagsPropertyValues(metaData);

        if (!tags.isEmpty()) {
            StepEventBus.getEventBus().addTagsToCurrentTest(tags);
        }
    }

    private Map<String, String> getMetadataFrom(Meta metaData) {
        Map<String, String> metadataValues = Maps.newHashMap();
        if (metaData == null) {
            return metadataValues;
        }

        for (String propertyName : metaData.getPropertyNames()) {
            metadataValues.put(propertyName, metaData.getProperty(propertyName));
        }
        return metadataValues;
    }

    private void registerMetadata(Meta metaData) {
        Serenity.getCurrentSession().clearMetaData();

        Map<String, String> scenarioMetadata = getMetadataFrom(metaData);
        scenarioMetadata.putAll(storyMetadata);
        for (String key : scenarioMetadata.keySet()) {
            Serenity.getCurrentSession().addMetaData(key, scenarioMetadata.get(key));
        }
    }

    private void registerStoryTags(Meta metaData) {
        List<TestTag> tags = getTagOrTagsPropertyValues(metaData);

        if (!tags.isEmpty()) {
            StepEventBus.getEventBus().addTagsToCurrentStory(tags);
        }
    }

    private void registerStoryMeta(Meta metaData) {
        if (isPending(metaData)) {
            StepEventBus.getEventBus().suspendTest();
        } else if (isSkipped(metaData)) {
            StepEventBus.getEventBus().suspendTest();
        } else if (isIgnored(metaData)) {
            StepEventBus.getEventBus().suspendTest();
        } else if (isManual(metaData)) {
            StepEventBus.getEventBus().suspendTest();
        }
    }

    private boolean isStoryManual() {
        return isManual(currentStory().getMeta());
    }

    private void registerScenarioMeta(Meta metaData) {

        // Manual can be combined with the other tags to override the default result category
        if (isManual(metaData) || isStoryManual()) {
            StepEventBus.getEventBus().testIsManual();
        }
    }

    private String removeSuffixFrom(String name) {
        return (name.contains(".")) ? name.substring(0, name.indexOf(".")) : name;
    }

    public void afterStory(boolean given) {
        logger.debug("afterStory " + given);
        shouldNestScenarios(false);
        if (given) {
            givenStoryMonitor.exitingGivenStory();
            givenStoryDone(currentStory());
        } else {
            if (isAfterStory(currentStory())) {
                generateReports();
            } else if (!isFixture(currentStory()) && (!isAStoryLevelGiven(currentStory()))) {
                StepEventBus.getEventBus().testSuiteFinished();
                clearListeners();
            }
        }

        storyStack.pop();
    }

    private boolean isAfterStory(Story currentStory) {
        return (currentStory.getName().equals(AFTER_STORIES));
    }

    private synchronized void generateReports() {
        getReportService().generateReportsFor(getAllTestOutcomes());
    }

    public List<TestOutcome> getAllTestOutcomes() {

        return baseStepListeners.stream()
                .map(BaseStepListener::getTestOutcomes)
                .flatMap(outcomes -> outcomes.stream())
                .collect(Collectors.toList());
    }

    public void narrative(Narrative narrative) {
        logger.debug("narrative ".concat(narrative.toString()));
    }

    public void lifecyle(Lifecycle lifecycle) {
        logger.debug("lifecyle ".concat(lifecycle.toString()));
    }

    public void scenarioNotAllowed(Scenario scenario, String s) {
        logger.debug("scenarioNotAllowed ".concat(scenario.getTitle()));
        StepEventBus.getEventBus().testIgnored();
    }

    private void startScenarioCalled(String scenarioTitle, String scenarioId) {
        StepEventBus.getEventBus().setTestSource(StepEventBus.TEST_SOURCE_JBEHAVE);
        StepEventBus.getEventBus().testStarted(scenarioTitle, scenarioId);
        activeScenarios.add(scenarioTitle);
    }

    private boolean shouldResetStepsBeforeEachScenario() {
        return systemConfiguration.getEnvironmentVariables().getPropertyAsBoolean(
                SerenityJBehaveSystemProperties.RESET_STEPS_EACH_SCENARIO.getName(), true);
    }

    List<String> scenarioTags;

    public void scenarioMeta(Meta meta) {

        scenarioTags = new ArrayList<>(meta.getPropertyNames());
        scenarioTags.addAll(currentStory().getMeta().getPropertyNames());

        final String title = activeScenarios.peek();
        logger.debug("scenario:\"" + (StringUtils.isEmpty(title) ? " don't know name " : title) + "\" registering metadata for" + meta);
        registerIssues(meta);
        registerFeaturesAndEpics(meta);
        registerTags(meta);
        registerMetadata(meta);
        registerScenarioMeta(meta);

        markAsSkippedOrPendingIfAnnotatedAsSuchIn(scenarioTags);
    }

    private void markAsSkippedOrPendingIfAnnotatedAsSuchIn(List<String> tags) {
        if (isManual(tags)) {
            StepEventBus.getEventBus().testIsManual();
        }
        if (isSkipped(tags)) {
            StepEventBus.getEventBus().testSkipped();
            StepEventBus.getEventBus().getBaseStepListener().overrideResultTo(TestResult.SKIPPED);
        }
        if (isPending(tags)) {
            StepEventBus.getEventBus().testPending();
            StepEventBus.getEventBus().getBaseStepListener().overrideResultTo(TestResult.PENDING);
        }
        if (isIgnored(tags)) {
            StepEventBus.getEventBus().testIgnored();
            StepEventBus.getEventBus().getBaseStepListener().overrideResultTo(TestResult.IGNORED);
        }
    }

    private boolean isSkipped(List<String> tags) {
        return tags.contains("skip") || tags.contains("wip");
    }

    private boolean isPending(List<String> tags) {
        return tags.contains("pending");
    }

    private boolean isIgnored(List<String> tags) {
        return tags.contains("ignore");
    }

    private boolean isManual(List<String> tags) {
        return tags.contains("manual");
    }

    private boolean isPending(Meta metaData) {
        if (metaData == null) return false;

        return (metaData.hasProperty(PENDING));
    }

    private boolean isManual(Meta metaData) {
        if (metaData == null) return false;

        return (metaData.hasProperty(MANUAL));
    }

    private boolean isSkipped(Meta metaData) {
        if (metaData == null) return false;

        return (metaData.hasProperty(WIP) || metaData.hasProperty(SKIP));
    }

    private boolean isCandidateToBeExecuted(Meta metaData) {
        return !isIgnored(metaData) && !isPending(metaData) && !isSkipped(metaData);
    }

    private boolean isIgnored(Meta metaData) {
        if (metaData == null) return false;

        return (metaData.hasProperty(IGNORE));
    }

    public void afterScenario() {
        final String scenarioTitle = activeScenarios.peek();
        logger.debug("afterScenario : " + activeScenarios.peek());
        scenarioMeta(scenarioMeta.get(scenarioTitle));
        scenarioMetaProcessed.add(scenarioTitle);


        if (givenStoryMonitor.isInGivenStory() || shouldNestScenarios()) {
            StepEventBus.getEventBus().stepFinished();
        } else {
            if (!(isPending(scenarioTags) || isSkipped(scenarioTags) || isIgnored(scenarioTags))) {
                StepEventBus.getEventBus().testFinished();
            }
            activeScenarios.pop();
        }

        ThucydidesWebDriverSupport.clearStepLibraries();
    }

    public void givenStories(GivenStories givenStories) {
        logger.debug("givenStories " + givenStories);
        givenStoryMonitor.enteringGivenStory();
    }

    public void givenStories(List<String> strings) {
        logger.debug("givenStories " + strings);
    }

    int exampleCount = 0;

    public void beforeExamples(List<String> steps, ExamplesTable table) {
        logger.debug("beforeExamples " + steps + " " + table);
        if (givenStoryMonitor.isInGivenStory()) {
            return;
        }

        exampleCount = 0;
        StepEventBus.getEventBus().useExamplesFrom(serenityTableFrom(table));
    }

    private DataTable serenityTableFrom(ExamplesTable table) {
        String scenarioOutline = scenarioOutlineFrom(currentScenario());
        return DataTable.withHeaders(table.getHeaders())
                .andScenarioOutline(scenarioOutline)
                .andMappedRows(table.getRows())
                .build();

    }

    private String scenarioOutlineFrom(Optional<Scenario> scenario) {
        if (!scenario.isPresent()) {
            return null;
        }
        StringBuilder outline = new StringBuilder();
        for (String step : scenario.get().getSteps()) {
            outline.append(step.trim()).append(System.lineSeparator());
        }
        return outline.toString();
    }

    public void example(Map<String, String> tableRow) {
        StepEventBus.getEventBus().clearStepFailures();

        if (givenStoryMonitor.isInGivenStory()) {
            return;
        }

        if (executingExamples()) {
            finishExample();
        }
        exampleCount++;
        startExample(tableRow);
    }

    private void startExample(Map<String, String> data) {
        StepEventBus.getEventBus().exampleStarted(data);
    }

    private void finishExample() {
        StepEventBus.getEventBus().exampleFinished();
    }

    private boolean executingExamples() {
        return (exampleCount > 0);
    }

    public void afterExamples() {
        if (givenStoryMonitor.isInGivenStory()) {
            return;
        }

        finishExample();
    }

    public void beforeStep(String stepTitle) {
        StepEventBus.getEventBus().stepStarted(ExecutedStepDescription.withTitle(stepTitle));
    }

    public void successful(String title) {
        if (annotatedResultTakesPriority()) {
            processAnnotatedResult();
        } else {
            StepEventBus.getEventBus().updateCurrentStepTitle(normalized(title));
            StepEventBus.getEventBus().stepFinished();
        }
    }

    private void processAnnotatedResult() {
        TestResult forcedResult = StepEventBus.getEventBus().getForcedResult().get();
        switch (forcedResult) {
            case PENDING:
                StepEventBus.getEventBus().stepPending();
                break;
            case IGNORED:
                StepEventBus.getEventBus().stepIgnored();
                break;
            case SKIPPED:
                StepEventBus.getEventBus().stepIgnored();
                break;
            default:
                StepEventBus.getEventBus().stepIgnored();
        }

    }

    private boolean annotatedResultTakesPriority() {
        return StepEventBus.getEventBus().getForcedResult().isPresent();
    }

    public void ignorable(String title) {
        StepEventBus.getEventBus().updateCurrentStepTitle(normalized(title));
        StepEventBus.getEventBus().stepIgnored();
    }

    @Override
    public void comment(String step) {
        StepEventBus.getEventBus().stepStarted(ExecutedStepDescription.withTitle(step));
        StepEventBus.getEventBus().stepIgnored();
    }

    public void pending(String stepTitle) {
        StepEventBus.getEventBus().stepStarted(ExecutedStepDescription.withTitle(normalized(stepTitle)));
        StepEventBus.getEventBus().stepPending();

    }

    public void notPerformed(String stepTitle) {
        StepEventBus.getEventBus().stepStarted(ExecutedStepDescription.withTitle(normalized(stepTitle)));
        StepEventBus.getEventBus().stepIgnored();
    }

    public void failed(String stepTitle, Throwable cause) {
        if (!StepEventBus.getEventBus().testSuiteHasStarted()) {
            declareOutOfSuiteFailure();
        }

        if (!errorOrFailureRecordedForStep(cause.getCause())) {
            StepEventBus.getEventBus().updateCurrentStepTitle(stepTitle);
            Throwable rootCause = new RootCauseAnalyzer(cause.getCause()).getRootCause().toException();

            if (isAssumptionFailure(rootCause)) {
                StepEventBus.getEventBus().assumptionViolated(rootCause.getMessage());
            } else {
                StepEventBus.getEventBus().stepFailed(new StepFailure(ExecutedStepDescription.withTitle(normalized(stepTitle)), rootCause));
            }
        }

    }

    private void declareOutOfSuiteFailure() {
        String storyName = !storyStack.isEmpty() ? storyStack.peek().getName() : "Before or After Story";
        String storyId = !storyStack.isEmpty() ? storyStack.peek().getPath() : null;
        StepEventBus.getEventBus().testStarted(storyName, storyId);
    }

    private boolean isAssumptionFailure(Throwable rootCause) {
        return (AssumptionViolatedException.class.isAssignableFrom(rootCause.getClass()));
    }

    public List<String> processExcludedByFilter(final Story story, final Set<String> exclude) {
        final Meta storyMeta = story.getMeta();
        final List<Scenario> processing = new LinkedList<>();
        final List<String> processed = new LinkedList<>();

        if (isSkipped(storyMeta) || isIgnored(storyMeta)) { //this story should be excluded by filter
            processing.addAll(story.getScenarios());
        } else {
            for (Scenario scenario : story.getScenarios()) {
                final Meta scenarioMeta = scenario.getMeta();
                if (isSkipped(scenarioMeta) || isIgnored(scenarioMeta)) { //this scenario should be excluded by filter
                    processing.add(scenario);
                }
            }
        }
        if (processing.size() > 0) {
            final Story beforeStory = new Story();
            beforeStory.namedAs(BEFORE_STORIES);
            final Story afterStory = new Story();
            afterStory.namedAs(AFTER_STORIES);

            final Narrative narrative = story.getNarrative();
            beforeStory(beforeStory, false);
            afterStory(false);
            beforeStory(story, false);
            narrative(narrative);
            for (final Scenario filtered : processing) {
                final String scenarioKey = scenarioKey(story, filtered);
                if (!exclude.contains(scenarioKey)) {

                    beforeScenario(filtered.getTitle());
                    scenarioMeta(filtered.getMeta());

                    final List<String> steps = filtered.getSteps();
                    if (ExamplesTable.EMPTY == filtered.getExamplesTable() || filtered.getExamplesTable().getRows().size() == 0) {
                        for (final String step : steps) {
                            beforeStep(step);
                            successful(step);
                        }
                    } else {
                        final ExamplesTable examples = filtered.getExamplesTable();
                        beforeExamples(steps, examples);
                        for (final Map<String, String> row : examples.getRows()) {
                            example(row);
                            for (final String step : steps) {
                                beforeStep(step);
                                successful(step);
                            }
                        }
                        afterExamples();
                    }
                    afterScenario();
                    processed.add(scenarioKey(story, filtered));
                }
            }
            afterStory(false);
            beforeStory(afterStory, false);
            afterStory(false);
        }
        return processed;
    }

    private String scenarioKey(final Story story, final Scenario scenario) {
        return story.getPath().concat(scenario.getTitle());
    }

    public void failedOutcomes(String s, OutcomesTable outcomesTable) {
        logger.debug("failedOutcomes");
    }

    public void restarted(String s, Throwable throwable) {
        logger.debug("restarted");
    }

    @Override
    public void restartedStory(Story story, Throwable cause) {
        logger.debug("restartedStory");
    }

    public void dryRun() {
        logger.debug("dryRun");
    }

    public void pendingMethods(List<String> strings) {
        logger.debug("pendingMethods");
    }

    private String normalized(String value) {
        return value.replaceAll(OPEN_PARAM_CHAR, "{").replaceAll(CLOSE_PARAM_CHAR, "}");

    }

    private boolean errorOrFailureRecordedForStep(Throwable cause) {
        if (!latestTestOutcome().isPresent()) {
            return false;
        }

        for (TestStep step : latestTestOutcome().get().getFlattenedTestSteps()) {
            if ((step.getException() != null) && (step.getException().getOriginalCause() == cause)) {
                return true;
            }
        }
        return false;
    }

    private java.util.Optional<TestOutcome> latestTestOutcome() {
        List<TestOutcome> recordedOutcomes = StepEventBus.getEventBus().getBaseStepListener().getTestOutcomes();
        return (recordedOutcomes.isEmpty()) ? java.util.Optional.<TestOutcome>empty()
                : java.util.Optional.of(recordedOutcomes.get(recordedOutcomes.size() - 1));
    }

}
