package org.rabix.engine.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.rabix.bindings.model.dag.DAGLinkPort;
import org.rabix.bindings.model.dag.DAGLinkPort.LinkPortType;
import org.rabix.engine.dao.JobRecordRepository;
import org.rabix.engine.model.JobRecord;
import org.rabix.engine.model.JobRecord.PortCounter;
import org.rabix.engine.service.cache.JobRecordCache;
import org.rabix.engine.service.cache.JobRecordCache.Action;
import org.rabix.engine.service.cache.JobRecordCache.CacheItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

public class JobRecordService {

  private final static Logger logger = LoggerFactory.getLogger(JobRecordService.class);
  
  public static AtomicInteger updateServiceCount = new AtomicInteger(0);
  public static AtomicInteger updateDBCount = new AtomicInteger(0);
  
  public static enum JobState {
    PENDING,
    READY,
    RUNNING,
    COMPLETED,
    FAILED
  }

  private JobRecordCache cache;
  private JobRecordRepository jobRecordRepository;
  
  @Inject
  public JobRecordService(JobRecordRepository jobRecordRepository, JobRecordCache cache) {
    this.cache = cache;
    this.jobRecordRepository = jobRecordRepository;
  }
  
  public static String generateUniqueId() {
    return UUID.randomUUID().toString();
  }
  
  public void create(JobRecord jobRecord) {
    cache.put(jobRecord, Action.INSERT);
  }

  public void delete(String rootId) {
  }
  
  public void update(JobRecord jobRecord) {
    updateServiceCount.incrementAndGet();
    cache.put(jobRecord, Action.UPDATE);
  }
  
  public void flushCache() {
    Collection<CacheItem> items = cache.getCacheItems();
    
    List<CacheItem> inserts = new ArrayList<>();
    List<CacheItem> updates = new ArrayList<>();
    
    for (CacheItem item : items) {
      switch (item.action) {
      case INSERT:
        inserts.add(item);
        break;
      case UPDATE:
        updates.add(item);
        break;
      default:
        break;
      }
    }
    
    for (CacheItem item : inserts) {
      jobRecordRepository.insert(item.jobRecord);
    }
    for (CacheItem item : updates) {
      updateDBCount.incrementAndGet();
      jobRecordRepository.update(item.jobRecord);
    }
    cache.clear();
    
    System.out.println("Service: " + updateServiceCount.get() + ", DB: " + updateDBCount.get());
  }

  // get from DB and put to cache with UPDATE action - don't override
  public List<JobRecord> findByParent(String parentId, String contextId) {
    return jobRecordRepository.getByParent(parentId, contextId);
  }
  
  public JobRecord find(String id, String contextId) {
    JobRecord record = cache.get(id, contextId);
    if (record != null) {
      return record;
    }
    record = jobRecordRepository.get(id, contextId);
    cache.put(record);
    return record;
  }
  
  //get from DB
  public JobRecord findRoot(String contextId) {
    return jobRecordRepository.getRoot(contextId);
  }
  
  public void increaseInputPortIncoming(JobRecord jobRecord, String port) {
    for (PortCounter portCounter : jobRecord.getInputCounters()) {
      if (portCounter.port.equals(port)) {
        portCounter.incoming++;
        return;
      }
    }
  }
  
  public void increaseOutputPortIncoming(JobRecord jobRecord, String port) {
    for (PortCounter portCounter : jobRecord.getOutputCounters()) {
      if (portCounter.port.equals(port)) {
        portCounter.incoming++;
        return;
      }
    }
  }
  
  public void incrementPortCounter(JobRecord jobRecord, DAGLinkPort port, LinkPortType type) {
    List<PortCounter> counters = type.equals(LinkPortType.INPUT) ? jobRecord.getInputCounters() : jobRecord.getOutputCounters();

    for (PortCounter pc : counters) {
      if (pc.port.equals(port.getId())) {
        if (type.equals(LinkPortType.INPUT)) {
          pc.counter = pc.counter + 1;
        } else {
          if (pc.updatedAsSourceCounter > 0) {
            pc.updatedAsSourceCounter = pc.updatedAsSourceCounter--;
            return;
          } else { 
            if (type.equals(LinkPortType.OUTPUT)) {
              if (jobRecord.isScatterWrapper()) {
                pc.counter = pc.counter + 1;
              } else if (jobRecord.isContainer() && pc.incoming > 1) {
                pc.counter = pc.counter + 1;
              }
            }
          }
        }
        return;
      }
    }
    PortCounter portCounter = new PortCounter(port.getId(), 1, port.isScatter());
    counters.add(portCounter);
  }
  
  public void decrementPortCounter(JobRecord jobRecord, String portId, LinkPortType type) {
    logger.info("JobRecord {}. Decrementing port {}.", jobRecord.getId(), portId);
    List<PortCounter> counters = type.equals(LinkPortType.INPUT) ? jobRecord.getInputCounters() : jobRecord.getOutputCounters();
    for (PortCounter portCounter : counters) {
      if (portCounter.port.equals(portId)) {
        portCounter.counter = portCounter.counter - 1;
      }
    }
    printInputPortCounters(jobRecord);
    printOutputPortCounters(jobRecord);
  }
  
  private void printInputPortCounters(JobRecord jobRecord) {
    StringBuilder builder = new StringBuilder("\nJob ").append(jobRecord.getId()).append(" input counters:\n");
    for (PortCounter inputPortCounter : jobRecord.getInputCounters()) {
      builder.append(" -- Input port ").append(inputPortCounter.getPort()).append(", counter=").append(inputPortCounter.counter).append("\n");
    }
    logger.debug(builder.toString());
  }
  
  private void printOutputPortCounters(JobRecord jobRecord) {
    StringBuilder builder = new StringBuilder("\nJob ").append(jobRecord.getId()).append(" output counters:\n");
    for (PortCounter inputPortCounter : jobRecord.getOutputCounters()) {
      builder.append(" -- Output port ").append(inputPortCounter.getPort()).append(", counter=").append(inputPortCounter.counter).append("\n");
    }
    logger.debug(builder.toString());
  }
  
  public void resetInputPortCounters(JobRecord jobRecord, int value) {
    if (jobRecord.getNumberOfGlobalInputs() == value) {
      return;
    }
    int oldValue = jobRecord.getNumberOfGlobalInputs();
    if (jobRecord.getNumberOfGlobalInputs() < value) {
      jobRecord.setNumberOfGlobalInputs(value);

      for (PortCounter pc : jobRecord.getInputCounters()) {
        if (pc.counter != value) {
          if (pc.counter == 0) {
            continue;
          }
          if (oldValue != 0) {
            pc.counter = jobRecord.getNumberOfGlobalInputs() - (oldValue - pc.counter);
          } else {
            pc.counter = jobRecord.getNumberOfGlobalInputs();
          }
        }
      }
    }
  }
  
  public void resetOutputPortCounter(JobRecord jobRecord, int value, String port) {
    logger.info("Reset output port counter {} for {} to {}", port, jobRecord.getId(), value);
    for (PortCounter pc : jobRecord.getOutputCounters()) {
      if (pc.port.equals(port)) {
        int oldValue = pc.globalCounter;
        if (pc.globalCounter < value) {
          pc.globalCounter = value;

          if (pc.counter == 0) {
            continue;
          }
          if (pc.counter != value) {
            if (oldValue != 0) {
              pc.counter = pc.globalCounter - (oldValue - pc.counter);
            } else {
              pc.counter = pc.globalCounter;
            }
          }
        }
      }
    }
  }
  
  public void resetOutputPortCounters(JobRecord jobRecord, int value) {
    logger.info("Reset output port counters for {} to {}", jobRecord.getId(), value);
    if (jobRecord.getNumberOfGlobalOutputs() == value) {
      return;
    }
    int oldValue = jobRecord.getNumberOfGlobalOutputs();
    if (jobRecord.getNumberOfGlobalOutputs() < value) {
      jobRecord.setNumberOfGlobalOutputs(value);

      for (PortCounter pc : jobRecord.getOutputCounters()) {
        if (pc.counter == 0) {
          continue;
        }
        if (pc.counter != value) {
          if (oldValue != 0) {
            pc.counter = jobRecord.getNumberOfGlobalOutputs() - (oldValue - pc.counter);
          } else {
            pc.counter = jobRecord.getNumberOfGlobalOutputs();
          }
        }
      }
    }
  }
  
}
