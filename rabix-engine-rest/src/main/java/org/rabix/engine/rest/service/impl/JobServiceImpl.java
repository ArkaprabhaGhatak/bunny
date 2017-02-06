package org.rabix.engine.rest.service.impl;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.configuration.Configuration;
import org.rabix.bindings.Bindings;
import org.rabix.bindings.BindingsFactory;
import org.rabix.bindings.model.Job;
import org.rabix.bindings.model.Job.JobStatus;
import org.rabix.bindings.model.Resources;
import org.rabix.bindings.model.dag.DAGNode;
import org.rabix.common.SystemEnvironmentHelper;
import org.rabix.engine.JobHelper;
import org.rabix.engine.db.DAGNodeDB;
import org.rabix.engine.db.JobDB;
import org.rabix.engine.event.impl.InitEvent;
import org.rabix.engine.event.impl.JobStatusEvent;
import org.rabix.engine.model.JobRecord;
import org.rabix.engine.processor.EventProcessor;
import org.rabix.engine.rest.helpers.IntermediaryFilesHelper;
import org.rabix.engine.rest.service.IntermediaryFilesService;
import org.rabix.engine.rest.service.JobService;
import org.rabix.engine.rest.service.JobServiceException;
import org.rabix.engine.rest.service.SchedulerService;
import org.rabix.engine.service.ContextRecordService;
import org.rabix.engine.service.JobRecordService;
import org.rabix.engine.service.JobRecordService.JobState;
import org.rabix.engine.service.LinkRecordService;
import org.rabix.engine.service.VariableRecordService;
import org.rabix.engine.status.EngineStatusCallback;
import org.rabix.engine.status.EngineStatusCallbackException;
import org.rabix.engine.validator.JobStateValidationException;
import org.rabix.engine.validator.JobStateValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

public class JobServiceImpl implements JobService {

  private final static Logger logger = LoggerFactory.getLogger(JobServiceImpl.class);
  
  private final JobRecordService jobRecordService;
  private final LinkRecordService linkRecordService;
  private final VariableRecordService variableRecordService;
  private final ContextRecordService contextRecordService;
  
  private final JobDB jobDB;
  private final DAGNodeDB dagNodeDB;
  
  private final EventProcessor eventProcessor;
  private final SchedulerService scheduler;
  private final IntermediaryFilesService intermediaryFilesService;
  
  private final ExecutorService executorService = Executors.newSingleThreadExecutor();

  private boolean isLocalBackend;
  private boolean deleteFilesUponExecution;
  private boolean deleteIntermediaryFiles;
  private boolean keepInputFiles;
  
  @Inject
  public JobServiceImpl(EventProcessor eventProcessor, JobRecordService jobRecordService, VariableRecordService variableRecordService, LinkRecordService linkRecordService, ContextRecordService contextRecordService, SchedulerService scheduler, IntermediaryFilesService intermediaryFilesService, Configuration configuration, DAGNodeDB dagNodeDB, JobDB jobDB) {
    this.jobDB = jobDB;
    this.dagNodeDB = dagNodeDB;
    this.eventProcessor = eventProcessor;
    
    this.jobRecordService = jobRecordService;
    this.linkRecordService = linkRecordService;
    this.variableRecordService = variableRecordService;
    this.contextRecordService = contextRecordService;
    this.scheduler = scheduler;
    
    this.intermediaryFilesService = intermediaryFilesService;

    deleteFilesUponExecution = configuration.getBoolean("rabix.delete_files_upon_execution", false);
    
    deleteIntermediaryFiles = configuration.getBoolean("rabix.delete_intermediary_files", false);
    keepInputFiles = configuration.getBoolean("rabix.keep_input_files", true);
    
    isLocalBackend = configuration.getBoolean("local.backend", false);
    this.eventProcessor.start(new EngineStatusCallbackImpl(isLocalBackend, isLocalBackend));
  }
  
  @Override
  public void update(Job job) throws JobServiceException {
    logger.debug("Update Job {}", job.getId());

    JobRecord jobRecord = jobRecordService.find(job.getName(), job.getRootId());
    try {
      JobStatusEvent statusEvent = null;
      JobStatus status = job.getStatus();
      switch (status) {
      case RUNNING:
        if (JobState.RUNNING.equals(jobRecord.getState())) {
          return;
        }
        JobStateValidator.checkState(jobRecord, JobState.RUNNING);
        statusEvent = new JobStatusEvent(job.getName(), job.getRootId(), JobState.RUNNING, job.getOutputs(), job.getId());
        break;
      case FAILED:
        if (JobState.FAILED.equals(jobRecord.getState())) {
          return;
        }
        JobStateValidator.checkState(jobRecord, JobState.FAILED);
        statusEvent = new JobStatusEvent(job.getName(), job.getRootId(), JobState.FAILED, null, job.getId());
        break;
      case COMPLETED:
        if (JobState.COMPLETED.equals(jobRecord.getState())) {
          return;
        }
        JobStateValidator.checkState(jobRecord, JobState.COMPLETED);
        statusEvent = new JobStatusEvent(job.getName(), job.getRootId(), JobState.COMPLETED, job.getOutputs(), job.getId());
        break;
      default:
        break;
      }
      jobDB.update(job);
      eventProcessor.addToExternalQueue(statusEvent, true);
    } catch (JobStateValidationException e) {
      // TODO handle exception
      logger.warn("Failed to update Job state from {} to {}", jobRecord.getState(), job.getStatus());
    }
  }
  
  @Override
  public Job start(Job job, Map<String, Object> config) throws JobServiceException {
    logger.debug("Start Job {}", job);
    
    String rootId = UUID.randomUUID().toString();
    job = Job.cloneWithIds(job, rootId, rootId);
    
    Bindings bindings = null;
    try {
      bindings = BindingsFactory.create(job);

      DAGNode node = bindings.translateToDAG(job);
      
      job = Job.cloneWithStatus(job, JobStatus.RUNNING);
      job = Job.cloneWithConfig(job, config);
      jobDB.add(job);

      InitEvent initEvent = new InitEvent(UUID.randomUUID().toString(), job.getConfig(), job.getRootId(), node, job.getInputs());
      eventProcessor.addToExternalQueue(initEvent, true);
      return job;
    } catch (Exception e) {
      logger.error("Failed to create Bindings", e);
      throw new JobServiceException("Failed to create Bindings", e);
    }
  }
  
  @Override
  public void stop(String id) throws JobServiceException {
    logger.debug("Stop Job {}", id);
    
    Job job = jobDB.get(id);
    if (job.isRoot()) {
      Set<Job> jobs = jobDB.getJobs(id);
      scheduler.stop(jobs.toArray(new Job[jobs.size()]));
    } else {
      scheduler.stop(job);
    }
  }
  
  @Override
  public Set<Job> getReady(EventProcessor eventProcessor, String contextId) throws JobServiceException {
    return JobHelper.createReadyJobs(jobRecordService, variableRecordService, linkRecordService, contextRecordService, dagNodeDB, contextId);
  }
  
  @Override
  public Set<Job> get() {
    return jobDB.getJobs();
  }

  @Override
  public Job get(String id) {
    return jobDB.get(id);
  }

  private class EngineStatusCallbackImpl implements EngineStatusCallback {

    private boolean stopOnFail;
    private boolean setResources;
    
    private static final long FREE_RESOURCES_WAIT_TIME = 3000L;
    
    private Set<String> stoppingRootIds = new HashSet<>();
    
    public EngineStatusCallbackImpl(boolean setResources, boolean stopOnFail) {
      this.stopOnFail = stopOnFail;
      this.setResources = setResources;
    }
    
    @Override
    public void onJobReady(Job job) {
      if (setResources) {
        long numberOfCores;
        long memory;
        if(job.getConfig() != null) {
          numberOfCores = job.getConfig().get("allocatedResources.cpu") != null ? Long.parseLong((String) job.getConfig().get("allocatedResources.cpu")) : SystemEnvironmentHelper.getNumberOfCores();
          memory = job.getConfig().get("allocatedResources.mem") != null ? Long.parseLong((String) job.getConfig().get("allocatedResources.mem")) : SystemEnvironmentHelper.getTotalPhysicalMemorySizeInMB();
        }
        else {
          numberOfCores = SystemEnvironmentHelper.getNumberOfCores();
          memory = SystemEnvironmentHelper.getTotalPhysicalMemorySizeInMB();
        }
        Resources resources = new Resources(numberOfCores, memory, null, true, null, null, null, null);
        job = Job.cloneWithResources(job, resources);
      }
      jobDB.update(job);
      scheduler.send(job);
    }
    
    @Override
    public void onJobsReady(Set<Job> jobs) throws EngineStatusCallbackException {
      for (Job job : jobs) {
        onJobReady(job);
      }
    }

    @Override
    public void onJobFailed(final Job failedJob) throws EngineStatusCallbackException {
      if (stopOnFail) {
        synchronized (stoppingRootIds) {
          if (stoppingRootIds.contains(failedJob.getRootId())) {
            return;
          }
          stoppingRootIds.add(failedJob.getRootId());
          
          try {
            stop(failedJob.getRootId());
          } catch (JobServiceException e) {
            logger.error("Failed to stop Root job " + failedJob.getRootId(), e);
          }
          executorService.submit(new Runnable() {
            @Override
            public void run() {
              while (true) {
                try {
                  boolean exit = true;
                  for (Job job : jobDB.getJobs(failedJob.getRootId())) {
                    if (!job.isRoot() && !isFinished(job.getStatus())) {
                      exit = false;
                      break;
                    }
                  }
                  if (exit) {
                    onJobRootFailed(failedJob);
                    break;
                  }
                  Thread.sleep(TimeUnit.SECONDS.toMillis(2));
                } catch (Exception e) {
                  logger.error("Failed to stop root Job " + failedJob.getRootId(), e);
                  break;
                }
              }
            }
          });
        }
      }
      if(deleteIntermediaryFiles) {
        IntermediaryFilesHelper.handleJobFailed(failedJob, jobDB.get(failedJob.getRootId()), intermediaryFilesService, keepInputFiles);
      }
    }
    
    private boolean isFinished(JobStatus jobStatus) {
      switch (jobStatus) {
      case COMPLETED:
      case FAILED:
      case ABORTED:
        return true;
      default:
        return false;
      }
    }
    
    public void onJobContainerReady(Job containerJob) {
      if(deleteIntermediaryFiles) {
        IntermediaryFilesHelper.handleContainerReady(containerJob, linkRecordService, intermediaryFilesService, keepInputFiles);
      }
    }

    @Override
    public void onJobRootCompleted(Job job) throws EngineStatusCallbackException {
      if (deleteFilesUponExecution) {
        scheduler.freeBackend(job);
        
        if (isLocalBackend) {
          try {
            Thread.sleep(FREE_RESOURCES_WAIT_TIME);
          } catch (InterruptedException e) { }
        }
      }
      
      job = Job.cloneWithStatus(job, JobStatus.COMPLETED);
      job = JobHelper.fillOutputs(job, jobRecordService, variableRecordService);
      jobDB.update(job);
    }

    @Override
    public void onJobRootFailed(Job job) throws EngineStatusCallbackException {
      synchronized (stoppingRootIds) {
        if (deleteFilesUponExecution) {
          scheduler.freeBackend(job);
          
          if (isLocalBackend) {
            try {
              Thread.sleep(FREE_RESOURCES_WAIT_TIME);
            } catch (InterruptedException e) { }
          }
        }
        
        job = Job.cloneWithStatus(job, JobStatus.FAILED);
        jobDB.update(job);

        scheduler.remove(job);
        stoppingRootIds.remove(job.getId());
      }
    }

    @Override
    public void onJobRootPartiallyCompleted(Job rootJob) throws EngineStatusCallbackException {
      logger.info("Root {} is partially completed.", rootJob.getId());
    }
    
    @Override
    public void onJobCompleted(Job job) throws EngineStatusCallbackException {
      logger.info("Job {} is completed.", job.getName());
      if (deleteIntermediaryFiles) {
         IntermediaryFilesHelper.handleJobCompleted(job, linkRecordService, intermediaryFilesService);
      }
    }
  }

}
