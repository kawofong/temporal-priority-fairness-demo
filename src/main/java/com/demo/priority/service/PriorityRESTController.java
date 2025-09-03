package com.demo.priority.service;
import com.demo.priority.service.model.*;
import com.demo.priority.service.workflows.PriorityWorkflow;
import com.demo.priority.service.workflows.FairnessWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.properties.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.demo.priority.service.model.WorkflowConfig;

import static io.temporal.client.WorkflowOptions.*;

@RestController
public class PriorityRESTController {
    private static final Logger logger = LoggerFactory.getLogger(PriorityRESTController.class);
    private ApplicationContext ctx;
    private String workflowTaskQueueName;

    @Autowired
    WorkflowClient client;


    @Autowired
    public PriorityRESTController(ApplicationContext applicationContext) {
        this.ctx = applicationContext;
        workflowTaskQueueName = this.getWorkflowTaskQueueName();
    }
    /**
     *
     * @param wfConfig
     * @return String
     *
     *  Uses a config object to define the workflow names and numbers to start.
     *  Will assume 100ms per WF start and then calculates a delay to the start
     *  time so that all workflows start at approx the same time.
     *
     */
    @PostMapping("start-workflows")
    public String startWorkflows(@RequestBody WorkflowConfig wfConfig) {
        if (wfConfig == null) { wfConfig = new WorkflowConfig();
            wfConfig.setNumberOfWorkflows(100);
            wfConfig.setWorkflowIdPrefix("Testing");
        }
        LocalDateTime startTime = this.getTargetWFStartTime(wfConfig.getNumberOfWorkflows());

        String mode = (wfConfig.getMode() == null) ? "priority" : wfConfig.getMode().trim().toLowerCase();
        if (!mode.equals("fairness")) {
            // Priority mode (default)
            for (int workflowNum = 1; workflowNum <  wfConfig.getNumberOfWorkflows() + 1; workflowNum++){
                logger.debug("Starting priority workflow {}-{} ", wfConfig.getWorkflowIdPrefix(), workflowNum);

                PriorityWorkflowData inputParameters = new PriorityWorkflowData();
                inputParameters.setPriority(((workflowNum - 1) % 5) + 1);

                SearchAttributes searchAttribs = SearchAttributes.newBuilder()
                        .set(SearchAttributeKey.forLong("Priority"), (long)inputParameters.getPriority())
                        .set(SearchAttributeKey.forLong("ActivitiesCompleted"), (long)0)
                        .build();

                PriorityWorkflow workflow = client.newWorkflowStub(
                        PriorityWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setTaskQueue(workflowTaskQueueName)
                                .setWorkflowId(wfConfig.getWorkflowIdPrefix() + "-" + workflowNum)
                                .setStartDelay(this.getStartDelay(startTime))
                                .setTypedSearchAttributes(searchAttribs)
                                .build()
                );

                WorkflowExecution priorityWFExecution = WorkflowClient.start(workflow::priorityWorkflow, inputParameters);
            }
        } else {
            // Fairness mode
            java.util.List<Band> bands = wfConfig.getBands();
            if (bands == null || bands.isEmpty()) {
                bands = new java.util.ArrayList<>();
                Band b1 = new Band(); b1.setKey("first-class");    b1.setWeight(15);
                Band b2 = new Band(); b2.setKey("business-class");  b2.setWeight(5);
                Band b3 = new Band(); b3.setKey("economy-class");   b3.setWeight(1);
                bands.add(b1); bands.add(b2); bands.add(b3);
            }

            for (int workflowNum = 1; workflowNum <  wfConfig.getNumberOfWorkflows() + 1; workflowNum++){
                Band band = bands.get((workflowNum - 1) % bands.size());
                logger.debug("Starting fairness workflow {}-{} [{}:{}]", wfConfig.getWorkflowIdPrefix(), workflowNum, band.getKey(), band.getWeight());

                FairnessWorkflowData inputParameters = new FairnessWorkflowData();
                inputParameters.setFairnessKey(band.getKey());
                inputParameters.setFairnessWeight(band.getWeight());

                SearchAttributes searchAttribs = SearchAttributes.newBuilder()
                        .set(SearchAttributeKey.forKeyword("FairnessKey"), band.getKey())
                        .set(SearchAttributeKey.forLong("FairnessWeight"), (long) band.getWeight())
                        .set(SearchAttributeKey.forLong("ActivitiesCompleted"), (long)0)
                        .build();

                FairnessWorkflow workflow = client.newWorkflowStub(
                        FairnessWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setTaskQueue("fairness-queue")
                                .setWorkflowId(wfConfig.getWorkflowIdPrefix() + "-" + workflowNum)
                                .setStartDelay(this.getStartDelay(startTime))
                                .setTypedSearchAttributes(searchAttribs)
                                .build()
                );

                WorkflowExecution wfExec = WorkflowClient.start(workflow::fairnessWorkflow, inputParameters);
            }
        }
        return "Done";
    } // End startWorkflows


    @GetMapping("run-status")
    public ResponseEntity<PriorityTestRunResults> getRunStatus(@RequestParam(required = true) String runPrefix) {

        Stream<WorkflowExecutionMetadata> workflowMetadata = client.listExecutions("WorkflowId STARTS_WITH \"" + runPrefix + "\"");
        List<WorkflowExecutionMetadata> wfList = (List<WorkflowExecutionMetadata>)workflowMetadata.toList();
        int numberWFInTest = wfList.size();
        PriorityTestRunResults results = new PriorityTestRunResults(wfList);
        logger.debug("There are [{}] in the test", numberWFInTest);

        return ResponseEntity.of(Optional.of(results));
    }   // End getRunStatus

    @GetMapping("run-status-fairness")
    public ResponseEntity<FairnessTestRunResults> getRunStatusFairness(@RequestParam(required = true) String runPrefix) {
        Stream<WorkflowExecutionMetadata> workflowMetadata = client.listExecutions("WorkflowId STARTS_WITH \"" + runPrefix + "\"");
        List<WorkflowExecutionMetadata> wfList = (List<WorkflowExecutionMetadata>)workflowMetadata.toList();
        FairnessTestRunResults results = new FairnessTestRunResults(wfList);
        return ResponseEntity.of(Optional.of(results));
    }


    private Duration getStartDelay(LocalDateTime pTargetStart)
    {
        return Duration.between(LocalDateTime.now(),pTargetStart);
    }// End GetStartDelay
    private LocalDateTime getTargetWFStartTime(int pNumWFInstancesToStart)
    {
        LocalDateTime currentTime = LocalDateTime.now();
        double secsToStartAll = (pNumWFInstancesToStart * 0.1) + 5; // Assuming 100ms to start each WF instance. + 5 secs to allow a bit of flex
        return currentTime.plusSeconds((long)secsToStartAll);
    }

    private String getWorkflowTaskQueueName()
    {
        // Parse the config to pick out the task queue for the activity. (Will be simpler once issue #1647 implemented)
        TemporalProperties props = ctx.getBean(TemporalProperties.class);
        Optional<WorkerProperties> wp =
                props.getWorkers().stream().filter(w -> w.getName().equals("PriorityWorkflow")).findFirst();
        return wp.get().getTaskQueue();
    }

}
