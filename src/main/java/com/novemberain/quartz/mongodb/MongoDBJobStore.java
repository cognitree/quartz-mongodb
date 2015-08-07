/*
 * $Id: MongoDBJobStore.java 253170 2014-01-06 02:28:03Z waded $
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package com.novemberain.quartz.mongodb;

import com.mongodb.*;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.UpdateOptions;
import org.apache.commons.codec.binary.Base64;
import org.bson.types.ObjectId;
import org.quartz.Calendar;
import org.quartz.*;
import org.quartz.Trigger.CompletedExecutionInstruction;
import org.quartz.Trigger.TriggerState;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.spi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.*;

import static com.novemberain.quartz.mongodb.Keys.*;

public class MongoDBJobStore implements JobStore, Constants {
  protected final Logger log = LoggerFactory.getLogger(getClass());

  public static final BasicDBObject KEY_AND_GROUP_FIELDS = new BasicDBObject()
      .append(KEY_GROUP, 1)
      .append(KEY_NAME, 1);

  @Deprecated
  private static MongoClient overriddenMongo;

  /**
   * @deprecated use {@link #MongoDBJobStore(MongoClient)}
   */
  @Deprecated
  public static void overrideMongo(MongoClient mongo) {
    overriddenMongo = mongo;
  }

  private MongoClient mongo;
  private String collectionPrefix = "quartz_";
  private String dbName;
  private String authDbName;
  private MongoCollection<BasicDBObject> jobCollection;
  private MongoCollection<BasicDBObject> triggerCollection;
  private MongoCollection<BasicDBObject> calendarCollection;
  private MongoCollection<BasicDBObject> locksCollection;
  private MongoCollection<BasicDBObject> pausedTriggerGroupsCollection;
  private MongoCollection<BasicDBObject> pausedJobGroupsCollection;
  private ClassLoadHelper loadHelper;
  private String instanceId;
  private String[] addresses;
  private String mongoUri;
  private String username;
  private String password;
  private SchedulerSignaler signaler;
  protected long misfireThreshold = 5000l;
  private long triggerTimeoutMillis = 10 * 60 * 1000L;
  private long jobTimeoutMillis = 10 * 60 * 1000L;
  
  // Options for the Mongo client.
  private Boolean mongoOptionSocketKeepAlive;
  private Integer mongoOptionMaxConnectionsPerHost;
  private Integer mongoOptionConnectTimeoutMillis; 
  private Integer mongoOptionSocketTimeoutMillis; // read timeout
  private Integer mongoOptionThreadsAllowedToBlockForConnectionMultiplier;

  private List<TriggerPersistenceHelper> persistenceHelpers;
  private QueryHelper queryHelper;

  public MongoDBJobStore(){

  }

  public MongoDBJobStore(final MongoClient mongo){
    this.mongo = mongo;
  }

  public MongoDBJobStore(final String mongoUri, final String username, final String password) {
    this.mongoUri = mongoUri;
    this.username = username;
    this.password = password;
  }

  public void initialize(ClassLoadHelper loadHelper, SchedulerSignaler signaler) throws SchedulerConfigException {
    this.loadHelper = loadHelper;
    this.signaler = signaler;
    if (this.mongo == null) {
      initializeMongo();
    } else {
      if (mongoUri != null  || username != null || password != null || addresses != null){
        throw new SchedulerConfigException("Configure either a Mongo instance or MongoDB connection parameters.");
      }
    }

    MongoDatabase db = selectDatabase(this.mongo);
    initializeCollections(db);
    ensureIndexes();

    initializeHelpers();
  }

  public void schedulerStarted() throws SchedulerException {
    // No-op
  }

  public void schedulerPaused() {
    // No-op
  }

  public void schedulerResumed() {
  }

  public void shutdown() {
    mongo.close();
  }

  public boolean supportsPersistence() {
    return true;
  }

  public long getEstimatedTimeToReleaseAndAcquireTrigger() {
    // this will vary...
    return 200;
  }

  public boolean isClustered() {
    return false;
  }

  /**
   * Job and Trigger storage Methods
   */
  public void storeJobAndTrigger(JobDetail newJob, OperableTrigger newTrigger) throws
      JobPersistenceException {
    ObjectId jobId = storeJobInMongo(newJob, false);

    log.debug("Storing job {} and trigger {}", newJob.getKey(), newTrigger.getKey());
    storeTrigger(newTrigger, jobId, false);
  }

  public void storeJob(JobDetail newJob, boolean replaceExisting) throws
      JobPersistenceException {
    storeJobInMongo(newJob, replaceExisting);
  }

  public void storeJobsAndTriggers(Map<JobDetail, List<Trigger>> triggersAndJobs, boolean replace)
      throws JobPersistenceException {
    throw new UnsupportedOperationException();
  }

  public boolean removeJob(JobKey jobKey) throws JobPersistenceException {
    BasicDBObject keyObject = Keys.keyToDBObject(jobKey);
    for (DBObject item : jobCollection.find(keyObject)) {
      jobCollection.deleteOne(keyObject);
      triggerCollection.deleteOne(new BasicDBObject(TRIGGER_JOB_ID, item.get("_id")));
      return true;
    }
    return false;
  }

  public boolean removeJobs(List<JobKey> jobKeys) throws JobPersistenceException {
    for (JobKey key : jobKeys) {
      removeJob(key);
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  public JobDetail retrieveJob(JobKey jobKey) throws JobPersistenceException {
    DBObject dbObject = findJobDocumentByKey(jobKey);
    if (dbObject == null) {
      //Return null if job does not exist, per interface
      return null;
    }

    try {
      Class<Job> jobClass = (Class<Job>) getJobClassLoader().loadClass((String) dbObject.get(JOB_CLASS));

      JobBuilder builder = JobBuilder.newJob(jobClass)
          .withIdentity((String) dbObject.get(KEY_NAME), (String) dbObject.get(KEY_GROUP))
          .withDescription((String) dbObject.get(JOB_DESCRIPTION));

      Object jobDurability = dbObject.get(JOB_DURABILITY);
      if (jobDurability != null) {
        if (jobDurability instanceof Boolean){
          builder.storeDurably((Boolean) jobDurability);
        } else if (jobDurability instanceof String){
          builder.storeDurably(Boolean.valueOf((String) jobDurability));
        } else {
          throw new JobPersistenceException("Illegal value for " + JOB_DURABILITY + ", class "
              + jobDurability.getClass() + " not supported");
        }
      }

      JobDataMap jobData = new JobDataMap();
      
      String jobDataString = (String) dbObject.get(JOB_DATA);
      
      if (jobDataString != null) {
        jobDataMapFromString(jobData, jobDataString);
      } else {
        for (String key : dbObject.keySet()) {
          if (!key.equals(KEY_NAME)
              && !key.equals(KEY_GROUP)
              && !key.equals(JOB_CLASS)
              && !key.equals(JOB_DESCRIPTION)
              && !key.equals(JOB_DURABILITY)
              && !key.equals("_id")) {
            jobData.put(key, dbObject.get(key));
          }
        }
      }

      jobData.clearDirtyFlag();
      
      return builder.usingJobData(jobData).build();
    } catch (ClassNotFoundException e) {
      throw new JobPersistenceException("Could not load job class " + dbObject.get(JOB_CLASS), e);
    }
    catch (IOException e) {
      throw new JobPersistenceException("Could not load job class " + dbObject.get(JOB_CLASS), e);
    }
  }

  public void storeTrigger(OperableTrigger newTrigger, boolean replaceExisting) throws
      JobPersistenceException {
    if (newTrigger.getJobKey() == null) {
      throw new JobPersistenceException("Trigger must be associated with a job. Please specify a JobKey.");
    }

    DBObject dbObject = jobCollection.find(Keys.keyToDBObject(newTrigger.getJobKey())).first();
    if (dbObject != null) {
      storeTrigger(newTrigger, (ObjectId) dbObject.get("_id"), replaceExisting);
    } else {
      throw new JobPersistenceException("Could not find job with key " + newTrigger.getJobKey());
    }
  }

  // If the removal of the Trigger results in an 'orphaned' Job that is not 'durable',
  // then the job should be removed also.
  public boolean removeTrigger(TriggerKey triggerKey) throws JobPersistenceException {
    BasicDBObject dbObject = Keys.keyToDBObject(triggerKey);
    List<DBObject> triggers = triggerCollection.find(dbObject).limit(2).into(new ArrayList<DBObject>(2));
    if (triggers.size() > 0) {
      DBObject trigger = triggers.get(0);
      if (trigger.containsField(TRIGGER_JOB_ID)) {
        // There is only 1 job per trigger so no need to look further.
        BasicDBObject job = jobCollection.find(new BasicDBObject("_id", trigger.get(TRIGGER_JOB_ID))).first();
        // Remove the orphaned job if it's durable and has no other triggers associated with it,
        // remove it
        if (job != null && (!job.containsField(JOB_DURABILITY) || job.get(JOB_DURABILITY).toString().equals("false"))) {
          List<DBObject> referencedTriggers = triggerCollection
                  .find(new BasicDBObject(TRIGGER_JOB_ID, job.get("_id")))
                  .limit(2)
                  .into(new ArrayList<DBObject>(2));
          if (referencedTriggers.size() == 1) {
            jobCollection.deleteOne(job);
          }
        }
      } else {
        log.debug("The trigger had no associated jobs");
      }
      //TODO: check if can .deleteOne(dbObject) here
      triggerCollection.deleteMany(dbObject);

      return true;
    }

    return false;
  }

  public boolean removeTriggers(List<TriggerKey> triggerKeys) throws JobPersistenceException {
    for (TriggerKey key : triggerKeys) {
      removeTrigger(key);
    }
    return false;
  }

  public boolean replaceTrigger(TriggerKey triggerKey, OperableTrigger newTrigger) throws JobPersistenceException {
    OperableTrigger trigger = retrieveTrigger(triggerKey);
    if(trigger == null) {
      return false;
    }

    if (!trigger.getJobKey().equals(newTrigger.getJobKey())) {
      throw new JobPersistenceException("New trigger is not related to the same job as the old trigger.");
    }

    // Can't call remove trigger as if the job is not durable, it will remove the job too
    BasicDBObject dbObject = Keys.keyToDBObject(triggerKey);
    if ((triggerCollection.count(dbObject)) > 0) {
      triggerCollection.deleteMany(dbObject);
    }
    
    // Copy across the job data map from the old trigger to the new one.
    newTrigger.getJobDataMap().putAll(trigger.getJobDataMap());
    
    try {
      storeTrigger(newTrigger, false);
    } catch(JobPersistenceException jpe) {
      storeTrigger(trigger, false); // put previous trigger back...
      throw jpe;
    }
    return true;
  }

  public OperableTrigger retrieveTrigger(TriggerKey triggerKey) throws JobPersistenceException {
    DBObject dbObject = triggerCollection.find(Keys.keyToDBObject(triggerKey)).first();
    if (dbObject == null) {
      return null;
    }
    return toTrigger(triggerKey, dbObject);
  }

  public boolean checkExists(JobKey jobKey) throws JobPersistenceException {
    return jobCollection.count(Keys.keyToDBObject(jobKey)) > 0;
  }

  public boolean checkExists(TriggerKey triggerKey) throws JobPersistenceException {
    return triggerCollection.count(Keys.keyToDBObject(triggerKey)) > 0;
  }

  public void clearAllSchedulingData() throws JobPersistenceException {
    //TODO: consider using coll.drop() here
    jobCollection.deleteMany(new BasicDBObject());
    triggerCollection.deleteMany(new BasicDBObject());
    calendarCollection.deleteMany(new BasicDBObject());
    pausedJobGroupsCollection.deleteMany(new BasicDBObject());
    pausedTriggerGroupsCollection.deleteMany(new BasicDBObject());
  }

  public void storeCalendar(String name,
                            Calendar calendar,
                            boolean replaceExisting,
                            boolean updateTriggers)
      throws JobPersistenceException {
    // TODO implement updating triggers
    if (updateTriggers) {
      throw new UnsupportedOperationException("Updating triggers is not supported.");
    }

    BasicDBObject dbObject = new BasicDBObject();
    dbObject.put(CALENDAR_NAME, name);
    dbObject.put(CALENDAR_SERIALIZED_OBJECT, serialize(calendar));

    calendarCollection.insertOne(dbObject);
  }

  public boolean removeCalendar(String calName) throws JobPersistenceException {
    BasicDBObject searchObj = new BasicDBObject(CALENDAR_NAME, calName);
    if (calendarCollection.count(searchObj) > 0) {
      calendarCollection.deleteMany(searchObj);
      return true;
    }
    return false;
  }

  public Calendar retrieveCalendar(String calName) throws JobPersistenceException {
    // TODO
    throw new UnsupportedOperationException();
  }

  public int getNumberOfJobs() throws JobPersistenceException {
    return (int) jobCollection.count();
  }

  public int getNumberOfTriggers() throws JobPersistenceException {
    return (int) triggerCollection.count();
  }

  public int getNumberOfCalendars() throws JobPersistenceException {
    return (int) calendarCollection.count();
  }

  public int getNumberOfLocks() {
    return (int) locksCollection.count();
  }

  public Set<JobKey> getJobKeys(GroupMatcher<JobKey> matcher) throws JobPersistenceException {
    BasicDBObject query = queryHelper.matchingKeysConditionFor(matcher);
    MongoCursor<BasicDBObject> cursor = jobCollection.find(query).projection(KEY_AND_GROUP_FIELDS).iterator();

    Set<JobKey> result = new HashSet<JobKey>();
    while (cursor.hasNext()) {
      DBObject dbo = cursor.next();
      JobKey key = Keys.dbObjectToJobKey(dbo);
      result.add(key);
    }

    return result;
  }

  public Set<TriggerKey> getTriggerKeys(GroupMatcher<TriggerKey> matcher) throws JobPersistenceException {
    BasicDBObject query = queryHelper.matchingKeysConditionFor(matcher);
    MongoCursor<BasicDBObject> cursor = triggerCollection.find(query).projection(KEY_AND_GROUP_FIELDS).iterator();

    Set<TriggerKey> result = new HashSet<TriggerKey>();
    while (cursor.hasNext()) {
      DBObject dbo = cursor.next();
      TriggerKey key = Keys.dbObjectToTriggerKey(dbo);
      result.add(key);
    }

    return result;
  }

  public List<String> getJobGroupNames() throws JobPersistenceException {
    return jobCollection.distinct(KEY_GROUP, String.class).into(new ArrayList<String>());
  }

  public List<String> getTriggerGroupNames() throws JobPersistenceException {
    return triggerCollection.distinct(KEY_GROUP, String.class).into(new ArrayList<String>());
  }

  public List<String> getCalendarNames() throws JobPersistenceException {
    throw new UnsupportedOperationException();
  }

  public List<OperableTrigger> getTriggersForJob(JobKey jobKey) throws JobPersistenceException {
    final List<OperableTrigger> triggers = new ArrayList<OperableTrigger>();
    final DBObject dbObject = findJobDocumentByKey(jobKey);
    if(dbObject  == null) {
      return triggers;
    }
    
    for (BasicDBObject item: triggerCollection.find(new BasicDBObject(TRIGGER_JOB_ID, dbObject.get("_id")))) {
      triggers.add(toTrigger(item));
    }

    return triggers;
  }

  public TriggerState getTriggerState(TriggerKey triggerKey) throws JobPersistenceException {
    DBObject doc = findTriggerDocumentByKey(triggerKey);

    return triggerStateForValue((String) doc.get(TRIGGER_STATE));
  }

  public void pauseTrigger(TriggerKey triggerKey) throws JobPersistenceException {
    triggerCollection.updateOne(Keys.keyToDBObject(triggerKey), updateThatSetsTriggerStateTo(STATE_PAUSED));
  }

  public Collection<String> pauseTriggers(GroupMatcher<TriggerKey> matcher) throws JobPersistenceException {
    final GroupHelper groupHelper = new GroupHelper(triggerCollection, queryHelper);
    triggerCollection.updateMany(
            queryHelper.matchingKeysConditionFor(matcher),
            updateThatSetsTriggerStateTo(STATE_PAUSED),
            new UpdateOptions().upsert(false));

    final Set<String> set = groupHelper.groupsThatMatch(matcher);
    markTriggerGroupsAsPaused(set);

    return set;
  }

  public void resumeTrigger(TriggerKey triggerKey) throws JobPersistenceException {
    // TODO: port blocking behavior and misfired triggers handling from StdJDBCDelegate in Quartz
    triggerCollection.updateOne(Keys.keyToDBObject(triggerKey), updateThatSetsTriggerStateTo(STATE_WAITING));
  }

  public Collection<String> resumeTriggers(GroupMatcher<TriggerKey> matcher) throws JobPersistenceException {
    final GroupHelper groupHelper = new GroupHelper(triggerCollection, queryHelper);
    triggerCollection.updateMany(
            queryHelper.matchingKeysConditionFor(matcher),
            updateThatSetsTriggerStateTo(STATE_WAITING),
            new UpdateOptions().upsert(false));

    final Set<String> set = groupHelper.groupsThatMatch(matcher);
    this.unmarkTriggerGroupsAsPaused(set);
    return set;
  }

  public Set<String> getPausedTriggerGroups() throws JobPersistenceException {
    return pausedTriggerGroupsCollection.distinct(KEY_GROUP, String.class).into(new HashSet<String>());
  }

  public Set<String> getPausedJobGroups() throws JobPersistenceException {
    return pausedJobGroupsCollection.distinct(KEY_GROUP, String.class).into(new HashSet<String>());
  }

  public void pauseAll() throws JobPersistenceException {
    final GroupHelper groupHelper = new GroupHelper(triggerCollection, queryHelper);
    triggerCollection.updateMany(new BasicDBObject(), updateThatSetsTriggerStateTo(STATE_PAUSED));
    this.markTriggerGroupsAsPaused(groupHelper.allGroups());
  }

  public void resumeAll() throws JobPersistenceException {
    final GroupHelper groupHelper = new GroupHelper(triggerCollection, queryHelper);
    triggerCollection.updateMany(new BasicDBObject(), updateThatSetsTriggerStateTo(STATE_WAITING));
    this.unmarkTriggerGroupsAsPaused(groupHelper.allGroups());
  }


  public void pauseJob(JobKey jobKey) throws JobPersistenceException {
    final ObjectId jobId = (ObjectId) findJobDocumentByKey(jobKey).get("_id");
    final TriggerGroupHelper groupHelper = new TriggerGroupHelper(triggerCollection, queryHelper);
    List<String> groups = groupHelper.groupsForJobId(jobId);
    triggerCollection.updateMany(new BasicDBObject(TRIGGER_JOB_ID, jobId), updateThatSetsTriggerStateTo(STATE_PAUSED));
    this.markTriggerGroupsAsPaused(groups);
  }

  public Collection<String> pauseJobs(GroupMatcher<JobKey> groupMatcher) throws JobPersistenceException {
    final TriggerGroupHelper groupHelper = new TriggerGroupHelper(triggerCollection, queryHelper);
    List<String> groups = groupHelper.groupsForJobIds(idsFrom(findJobDocumentsThatMatch(groupMatcher)));
    triggerCollection.updateMany(queryHelper.inGroups(groups), updateThatSetsTriggerStateTo(STATE_PAUSED));
    this.markJobGroupsAsPaused(groups);

    return groups;
  }

  public void resumeJob(JobKey jobKey) throws JobPersistenceException {
    final ObjectId jobId = (ObjectId) findJobDocumentByKey(jobKey).get("_id");
    // TODO: port blocking behavior and misfired triggers handling from StdJDBCDelegate in Quartz
    triggerCollection.updateMany(new BasicDBObject(TRIGGER_JOB_ID, jobId), updateThatSetsTriggerStateTo(STATE_WAITING));
  }

  public Collection<String> resumeJobs(GroupMatcher<JobKey> groupMatcher) throws JobPersistenceException {
    final TriggerGroupHelper groupHelper = new TriggerGroupHelper(triggerCollection, queryHelper);
    List<String> groups = groupHelper.groupsForJobIds(idsFrom(findJobDocumentsThatMatch(groupMatcher)));
    triggerCollection.updateMany(queryHelper.inGroups(groups), updateThatSetsTriggerStateTo(STATE_WAITING));
    this.unmarkJobGroupsAsPaused(groups);

    return groups;
  }


  public List<OperableTrigger> acquireNextTriggers(long noLaterThan, int maxCount, long timeWindow)
      throws JobPersistenceException {
    
    Date noLaterThanDate = new Date(noLaterThan + timeWindow);
    
    if (log.isDebugEnabled()) {
      log.debug("Finding up to {} triggers which have time less than {}", maxCount, noLaterThanDate);
    }
    
    Map<TriggerKey, OperableTrigger> triggers = new HashMap<TriggerKey, OperableTrigger>();
    
    doAcquireNextTriggers(triggers, noLaterThanDate, maxCount);
    
    List<OperableTrigger> triggerList = new LinkedList<OperableTrigger>(triggers.values());

    // Because we are handling a batch, we may have done multiple queries and while the result for each
    // query is in fire order, the result for the whole might not be, so sort them again
    
    Collections.sort(triggerList, new Comparator<OperableTrigger>() {

      @Override
      public int compare(OperableTrigger o1, OperableTrigger o2) {
        return (int) (o1.getNextFireTime().getTime() - o2.getNextFireTime().getTime());
      }});
    
    return triggerList;
  }
  
  private void doAcquireNextTriggers(Map<TriggerKey, OperableTrigger> triggers, Date noLaterThanDate, int maxCount)
      throws JobPersistenceException {
    BasicDBObject query = createNextTriggerQuery(noLaterThanDate);
    BasicDBObject sort = new BasicDBObject(TRIGGER_NEXT_FIRE_TIME, 1);

    for (BasicDBObject obj : triggerCollection.find(query).sort(sort)) {
      log.info("Found Trigger: {}", obj);
    }

    MongoCursor<BasicDBObject> cursor = triggerCollection.find(query).sort(sort).iterator();

    //if (log.isDebugEnabled()) {
    log.info("Found {} triggers which are eligible to be run.", triggerCollection.count(query));
    //}

    while (cursor.hasNext() && maxCount > triggers.size()) {
      DBObject dbObj = cursor.next();

      OperableTrigger trigger = toTrigger(dbObj);

      try {

        if (trigger == null) {
          continue;
        }

        if (triggers.containsKey(trigger.getKey()))
        {
          log.debug("Skipping trigger {} as we have already acquired it.", trigger.getKey());
          continue;
        }
        
        if (trigger.getNextFireTime() == null) {
          log.debug("Skipping trigger {} as it has no next fire time.", trigger.getKey());
          
          // No next fire time, so delete it
          removeTrigger(trigger.getKey());
          continue;
        }

        // deal with misfires
        if (applyMisfire(trigger)) {
          log.debug("Misfire trigger {}.", trigger.getKey());

          Date nextFireTime = trigger.getNextFireTime();
          
          if (nextFireTime == null) {
            log.debug("Removing trigger {} as it has no next fire time after the misfire was applied.", trigger.getKey());
            
            // No next fire time, so delete it
            removeTrigger(trigger.getKey());
            continue;
          }
          
          // The trigger has misfired and was rescheduled, its firetime may be too far in the future
          // and we don't want to hang the quartz scheduler thread up on <code>sigLock.wait(timeUntilTrigger);</code> 
          // so, check again that the trigger is due to fire
          if (nextFireTime.after(noLaterThanDate))
          {
            log.debug("Skipping trigger {} as it misfired and was scheduled for {}.",
                    trigger.getKey(), trigger.getNextFireTime());
            continue;
          }
        }
        
        log.info("Inserting lock for trigger {}", trigger.getKey());

        BasicDBObject lock = createTriggerDbLock(dbObj);
        log.info("Locks Collection write concern: {}", locksCollection.getWriteConcern());
        locksCollection.insertOne(lock);
        
        log.info("Aquired trigger {}", trigger.getKey());
        triggers.put(trigger.getKey(), trigger);
        
      } catch (DuplicateKeyException e) {

        // someone else acquired this lock. Move on.
        log.info("Failed to acquire trigger {} due to a lock", trigger.getKey());

        BasicDBObject lock = new BasicDBObject();
        lock.put(KEY_NAME, dbObj.get(KEY_NAME));
        lock.put(KEY_GROUP, dbObj.get(KEY_GROUP));

        DBObject existingLock;
        MongoCursor<BasicDBObject> lockCursor = locksCollection.find(lock).iterator();
        if (lockCursor.hasNext()) {
          existingLock = lockCursor.next();
          // support for trigger lock expirations
          if (isTriggerLockExpired(existingLock)) {
            log.warn("Lock for trigger {} is expired - removing lock and retrying trigger acquisition", trigger.getKey());
            removeTriggerLock(trigger);
            doAcquireNextTriggers(triggers, noLaterThanDate, maxCount - triggers.size());
          }
        } else {
          log.warn("Error retrieving expired lock from the database. Maybe it was deleted");
          doAcquireNextTriggers(triggers, noLaterThanDate, maxCount - triggers.size());
        }
      }
    }
  }

  private BasicDBObject createNextTriggerQuery(Date noLaterThanDate) {
    BasicDBObject query = new BasicDBObject();
    query.put(TRIGGER_NEXT_FIRE_TIME, new BasicDBObject("$lte", noLaterThanDate));
    query.put(TRIGGER_STATE, STATE_WAITING);
    return query;
  }

  private BasicDBObject createTriggerDbLock(DBObject dbObj) {
    BasicDBObject lock = new BasicDBObject();
    lock.put(KEY_NAME, dbObj.get(KEY_NAME));
    lock.put(KEY_GROUP, dbObj.get(KEY_GROUP));
    lock.put(LOCK_INSTANCE_ID, instanceId);
    lock.put(LOCK_TIME, new Date());
    return lock;
  }

  public void releaseAcquiredTrigger(OperableTrigger trigger) throws JobPersistenceException {
    try {
      removeTriggerLock(trigger);
    } catch (Exception e) {
      throw new JobPersistenceException(e.getLocalizedMessage(), e);
    }
  }

  public List<TriggerFiredResult> triggersFired(List<OperableTrigger> triggers) throws JobPersistenceException {

    List<TriggerFiredResult> results = new ArrayList<TriggerFiredResult>();

    for (OperableTrigger trigger : triggers) {
      log.debug("Fired trigger {}", trigger.getKey());
      Calendar cal = null;
      if (trigger.getCalendarName() != null) {
        cal = retrieveCalendar(trigger.getCalendarName());
        if (cal == null)
          continue;
      }

      Date prevFireTime = trigger.getPreviousFireTime();
      trigger.triggered(cal);

      TriggerFiredBundle bundle = new TriggerFiredBundle(retrieveJob(
          trigger), trigger, cal,
          false, new Date(), trigger.getPreviousFireTime(), prevFireTime,
          trigger.getNextFireTime());

      JobDetail job = bundle.getJobDetail();
      
      if (job != null) {
        
        try {
        
          if (job.isConcurrentExectionDisallowed()) {

            log.debug("Inserting lock for job {}", job.getKey());
            BasicDBObject lock = new BasicDBObject();
            lock.put(KEY_NAME, "jobconcurrentlock:" + job.getKey().getName());
            lock.put(KEY_GROUP, job.getKey().getGroup());
            lock.put(LOCK_INSTANCE_ID, instanceId);
            lock.put(LOCK_TIME, new Date());
            locksCollection.insertOne(lock);
          }
          
          results.add(new TriggerFiredResult(bundle));
          storeTrigger(trigger, true);
        }
        catch (DuplicateKeyException dk) {
          
          log.debug("Job disallows concurrent execution and is already running {}", job.getKey());
          
          // Remove the trigger lock
          removeTriggerLock(trigger);
          
          // Find the existing lock and if still present, and expired, then remove it.
          BasicDBObject lock = new BasicDBObject();
          lock.put(KEY_NAME, "jobconcurrentlock:" + job.getKey().getName());
          lock.put(KEY_GROUP, job.getKey().getGroup());
          
          BasicDBObject existingLock;
          MongoCursor<BasicDBObject> lockCursor = locksCollection.find(lock).iterator();
          if (lockCursor.hasNext()) {
            existingLock = lockCursor.next();
            
            if (isJobLockExpired(existingLock)) {
              log.debug("Removing expired lock for job {}", job.getKey());
              locksCollection.deleteOne(existingLock);
            }
          }
        }
      }

    }
    return results;
  }

  public void triggeredJobComplete(OperableTrigger trigger,
                                   JobDetail jobDetail,
                                   CompletedExecutionInstruction triggerInstCode)
      throws JobPersistenceException {
    
    log.debug("Trigger completed {}", trigger.getKey());
    
    if (jobDetail.isPersistJobDataAfterExecution()) {
      if (jobDetail.getJobDataMap().isDirty()) {
        log.debug("Job data map dirty, will store {}", jobDetail.getKey());
        storeJobInMongo(jobDetail, true);
      }
    }
    
    if (jobDetail.isConcurrentExectionDisallowed()) {
      log.debug("Removing lock for job {}", jobDetail.getKey());
      BasicDBObject lock = new BasicDBObject();
      lock.put(KEY_NAME, "jobconcurrentlock:" + jobDetail.getKey().getName());
      lock.put(KEY_GROUP, jobDetail.getKey().getGroup());
      locksCollection.deleteOne(lock);
    }
    
    // check for trigger deleted during execution...
    OperableTrigger trigger2 = retrieveTrigger(trigger.getKey());
    if (trigger2 != null) {
      if (triggerInstCode == CompletedExecutionInstruction.DELETE_TRIGGER) {
        if (trigger.getNextFireTime() == null) {
          // double check for possible reschedule within job
          // execution, which would cancel the need to delete...
          if (trigger2.getNextFireTime() == null) {
            removeTrigger(trigger.getKey());
          }
        } else {
          removeTrigger(trigger.getKey());
          signaler.signalSchedulingChange(0L);
        }
      } else if (triggerInstCode == CompletedExecutionInstruction.SET_TRIGGER_COMPLETE) {
        // TODO: need to store state
        signaler.signalSchedulingChange(0L);
      } else if (triggerInstCode == CompletedExecutionInstruction.SET_TRIGGER_ERROR) {
        // TODO: need to store state
        signaler.signalSchedulingChange(0L);
      } else if (triggerInstCode == CompletedExecutionInstruction.SET_ALL_JOB_TRIGGERS_ERROR) {
        // TODO: need to store state
        signaler.signalSchedulingChange(0L);
      } else if (triggerInstCode == CompletedExecutionInstruction.SET_ALL_JOB_TRIGGERS_COMPLETE) {
        // TODO: need to store state
        signaler.signalSchedulingChange(0L);
      }
    }

    removeTriggerLock(trigger);
  }

  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
  }

  public void setInstanceName(String schedName) {
    // No-op
  }

  public void setThreadPoolSize(int poolSize) {
    // No-op
  }

  public void setAddresses(String addresses) {
    this.addresses = addresses.split(",");
  }

  public MongoCollection<BasicDBObject> getJobCollection() {
    return jobCollection;
  }

  public MongoCollection<BasicDBObject> getTriggerCollection() {
    return triggerCollection;
  }

  public MongoCollection<BasicDBObject> getCalendarCollection() {
    return calendarCollection;
  }

  public MongoCollection<BasicDBObject> getLocksCollection() {
    return locksCollection;
  }

  public String getDbName() {
    return dbName;
  }

  public void setDbName(String dbName) {
    this.dbName = dbName;
  }

  public void setCollectionPrefix(String prefix) {
    collectionPrefix = prefix + "_";
  }

  public void setMongoUri(final String mongoUri) {
    this.mongoUri = mongoUri;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public long getMisfireThreshold() {
    return misfireThreshold;
  }

  public void setMisfireThreshold(long misfireThreshold) {
    this.misfireThreshold = misfireThreshold;
  }

  public void setTriggerTimeoutMillis(long triggerTimeoutMillis) {
    this.triggerTimeoutMillis = triggerTimeoutMillis;
  }

  public void setJobTimeoutMillis(long jobTimeoutMillis) {
    this.jobTimeoutMillis = jobTimeoutMillis;
  }

  //
  // Implementation
  //

  private void initializeMongo() throws SchedulerConfigException {
    if (overriddenMongo != null) {
      this.mongo = overriddenMongo;
    } else {
      this.mongo = connectToMongoDB();
    }
    if (this.mongo == null) {
      throw new SchedulerConfigException("Could not connect to MongoDB! Please check that quartz-mongodb configuration is correct.");
    }
  }

  private void initializeCollections(MongoDatabase db) {
    jobCollection = db.getCollection(collectionPrefix + "jobs", BasicDBObject.class);
    triggerCollection = db.getCollection(collectionPrefix + "triggers", BasicDBObject.class);
    calendarCollection = db.getCollection(collectionPrefix + "calendars", BasicDBObject.class);
    // A lock needs to be written with FSYNCED to be 100% effective across multiple servers
    locksCollection = db.getCollection(collectionPrefix + "locks", BasicDBObject.class)
            .withWriteConcern(WriteConcern.FSYNCED);

    pausedTriggerGroupsCollection = db.getCollection(collectionPrefix + "paused_trigger_groups", BasicDBObject.class);
    pausedJobGroupsCollection = db.getCollection(collectionPrefix + "paused_job_groups", BasicDBObject.class);
  }

  private MongoDatabase selectDatabase(MongoClient mongo) {
    // MongoDB defaults are insane, set a reasonable write concern explicitly. MK.
    // But we would be insane not to override this when writing lock records. LB.
    mongo.setWriteConcern(WriteConcern.JOURNALED);
    return mongo.getDatabase(dbName);
  }

  private MongoClient connectToMongoDB() throws SchedulerConfigException {
    if (mongoUri == null && (addresses == null || addresses.length == 0)) {
      throw new SchedulerConfigException("At least one MongoDB address or a MongoDB URI must be specified .");
    }

    if(mongoUri != null) {
      return connectToMongoDB(mongoUri);
    }
    
    MongoClientOptions.Builder optionsBuilder = MongoClientOptions.builder();
    optionsBuilder.writeConcern(WriteConcern.SAFE);
    
    if (mongoOptionMaxConnectionsPerHost != null) optionsBuilder.connectionsPerHost(mongoOptionMaxConnectionsPerHost);
    if (mongoOptionConnectTimeoutMillis != null) optionsBuilder.connectTimeout(mongoOptionConnectTimeoutMillis);
    if (mongoOptionSocketTimeoutMillis != null) optionsBuilder.socketTimeout(mongoOptionSocketTimeoutMillis);
    if (mongoOptionSocketKeepAlive != null) optionsBuilder.socketKeepAlive(mongoOptionSocketKeepAlive);
    if (mongoOptionThreadsAllowedToBlockForConnectionMultiplier != null) {
      optionsBuilder.threadsAllowedToBlockForConnectionMultiplier(mongoOptionThreadsAllowedToBlockForConnectionMultiplier);
    }

    List<MongoCredential> credentials = createCredentials();

    MongoClientOptions options = optionsBuilder.build();

    try {
      ArrayList<ServerAddress> serverAddresses = new ArrayList<ServerAddress>();
      for (String a : addresses) {
        serverAddresses.add(new ServerAddress(a));
      }
      return new MongoClient(serverAddresses, credentials, options);
    } catch (MongoException e) {
      throw new SchedulerConfigException("Could not connect to MongoDB", e);
    }
  }

  private List<MongoCredential> createCredentials() {
    List<MongoCredential> credentials = new ArrayList<MongoCredential>(1);
    if (username != null) {
      if(authDbName != null) {
        // authenticating to db which gives access to all other dbs (role - readWriteAnyDatabase)
        // by default in mongo it should be "admin"
        credentials.add(MongoCredential.createCredential(username, authDbName, password.toCharArray()));
      } else {
        credentials.add(MongoCredential.createCredential(username, dbName, password.toCharArray()));
      }
    }
    return credentials;
  }

  private MongoClient connectToMongoDB(final String mongoUriAsString) throws SchedulerConfigException {
    try {
      return new MongoClient(new MongoClientURI(mongoUriAsString));
   } catch (final MongoException e) {
      throw new SchedulerConfigException("MongoDB driver thrown an exception", e);
    }
  }

  protected OperableTrigger toTrigger(DBObject dbObj) throws JobPersistenceException {
    TriggerKey key = new TriggerKey((String) dbObj.get(KEY_NAME), (String) dbObj.get(KEY_GROUP));
    return toTrigger(key, dbObj);
  }

  protected OperableTrigger toTrigger(TriggerKey triggerKey, DBObject dbObject) throws JobPersistenceException {
    OperableTrigger trigger;
    try {
      @SuppressWarnings("unchecked")
      Class<OperableTrigger> triggerClass = (Class<OperableTrigger>) getTriggerClassLoader()
              .loadClass((String) dbObject.get(TRIGGER_CLASS));
      trigger = triggerClass.newInstance();
    } catch (ClassNotFoundException e) {
      throw new JobPersistenceException("Could not find trigger class " + dbObject.get(TRIGGER_CLASS));
    } catch (Exception e) {
      throw new JobPersistenceException("Could not instantiate trigger class " + dbObject.get(TRIGGER_CLASS));
    }

    TriggerPersistenceHelper tpd = triggerPersistenceDelegateFor(trigger);

    trigger.setKey(triggerKey);
    trigger.setCalendarName((String) dbObject.get(TRIGGER_CALENDAR_NAME));
    trigger.setDescription((String) dbObject.get(TRIGGER_DESCRIPTION));
    trigger.setFireInstanceId((String) dbObject.get(TRIGGER_FIRE_INSTANCE_ID));
    trigger.setMisfireInstruction((Integer) dbObject.get(TRIGGER_MISFIRE_INSTRUCTION));
    trigger.setNextFireTime((Date) dbObject.get(TRIGGER_NEXT_FIRE_TIME));
    trigger.setPreviousFireTime((Date) dbObject.get(TRIGGER_PREVIOUS_FIRE_TIME));
    trigger.setPriority((Integer) dbObject.get(TRIGGER_PRIORITY));
    
    String jobDataString = (String) dbObject.get(JOB_DATA);
    
    if (jobDataString != null) {
      try {
        jobDataMapFromString(trigger.getJobDataMap(), jobDataString);
      } catch (IOException e) {
        throw new JobPersistenceException("Could not deserialize job data for trigger " + dbObject.get(TRIGGER_CLASS));
      }
    }
    
    try {
        trigger.setStartTime((Date) dbObject.get(TRIGGER_START_TIME));
        trigger.setEndTime((Date) dbObject.get(TRIGGER_END_TIME));
    } catch(IllegalArgumentException e) {
        //Ignore illegal arg exceptions thrown by triggers doing JIT validation of start and endtime
        log.warn("Trigger had illegal start / end time combination: {}", trigger.getKey(), e);
    }


    try {
        trigger.setStartTime((Date) dbObject.get(TRIGGER_START_TIME));
        trigger.setEndTime((Date) dbObject.get(TRIGGER_END_TIME));
    } catch(IllegalArgumentException e) {
        //Ignore illegal arg exceptions thrown by triggers doing JIT validation of start and endtime
        log.warn("Trigger had illegal start / end time combination: {}", trigger.getKey(), e);
    }

    trigger = tpd.setExtraPropertiesAfterInstantiation(trigger, dbObject);

    DBObject job = jobCollection.find(new BasicDBObject("_id", dbObject.get(TRIGGER_JOB_ID))).first();
    if (job != null) {
      trigger.setJobKey(new JobKey((String) job.get(KEY_NAME), (String) job.get(KEY_GROUP)));
      return trigger;
    } else {
      // job was deleted
      return null;
    }
  }

  protected ClassLoader getTriggerClassLoader() {
    return org.quartz.Job.class.getClassLoader();
  }

  private TriggerPersistenceHelper triggerPersistenceDelegateFor(OperableTrigger trigger) {
    TriggerPersistenceHelper result = null;

    for (TriggerPersistenceHelper d : persistenceHelpers) {
      if (d.canHandleTriggerType(trigger)) {
        result = d;
        break;
      }
    }

    assert result != null;
    return result;
  }

  protected boolean isTriggerLockExpired(DBObject lock) {
    Date lockTime = (Date) lock.get(LOCK_TIME);
    long elaspedTime = System.currentTimeMillis() - lockTime.getTime();
    return (elaspedTime > triggerTimeoutMillis);
  }

  protected boolean isJobLockExpired(DBObject lock) {
    Date lockTime = (Date) lock.get(LOCK_TIME);
    long elaspedTime = System.currentTimeMillis() - lockTime.getTime();
    return (elaspedTime > jobTimeoutMillis);
  }

  protected boolean applyMisfire(OperableTrigger trigger) throws JobPersistenceException {
    long misfireTime = System.currentTimeMillis();
    if (getMisfireThreshold() > 0) {
      misfireTime -= getMisfireThreshold();
    }

    Date tnft = trigger.getNextFireTime();
    if (tnft == null || tnft.getTime() > misfireTime
        || trigger.getMisfireInstruction() == Trigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY) {
      return false;
    }

    Calendar cal = null;
    if (trigger.getCalendarName() != null) {
      cal = retrieveCalendar(trigger.getCalendarName());
    }

    signaler.notifyTriggerListenersMisfired((OperableTrigger) trigger.clone());

    trigger.updateAfterMisfire(cal);

    if (trigger.getNextFireTime() == null) {
      signaler.notifySchedulerListenersFinalized(trigger);
    } else if (tnft.equals(trigger.getNextFireTime())) {
      return false;
    }

    storeTrigger(trigger, true);
    return true;
  }


  private Object serialize(Calendar calendar) throws JobPersistenceException {
    ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
    try {
      ObjectOutputStream objectStream = new ObjectOutputStream(byteStream);
      objectStream.writeObject(calendar);
      objectStream.close();
      return byteStream.toByteArray();
    } catch (IOException e) {
      throw new JobPersistenceException("Could not serialize Calendar.", e);
    }
  }

  /**
   * Initializes the indexes for the scheduler collections.
   *
   * @throws SchedulerConfigException if an error occurred communicating with the MongoDB server.
   */
  private void ensureIndexes() throws SchedulerConfigException {
    try {
      /*
       * Indexes are to be declared as group then name.  This is important as the quartz API allows
       * for the searching of jobs and triggers using a group matcher.  To be able to use the compound
       * index using group alone (as the API allows), group must be the first key in that index.
       * 
       * To be consistent, all such indexes are ensured in the order group then name.  The previous
       * indexes are removed after we have "ensured" the new ones.
       */

      BasicDBObject keys = new BasicDBObject();
      keys.put(KEY_GROUP, 1);
      keys.put(KEY_NAME, 1);
      jobCollection.createIndex(keys, new IndexOptions().unique(true));

      keys = new BasicDBObject();
      keys.put(KEY_GROUP, 1);
      keys.put(KEY_NAME, 1);
      triggerCollection.createIndex(keys, new IndexOptions().unique(true));

      keys = new BasicDBObject();
      keys.put(KEY_GROUP, 1);
      keys.put(KEY_NAME, 1);
      locksCollection.createIndex(keys, new IndexOptions().unique(true));

      // Need this to stop table scan when removing all locks
      locksCollection.createIndex(new BasicDBObject(LOCK_INSTANCE_ID, 1));
      
      // remove all locks for this instance on startup
      locksCollection.deleteMany(new BasicDBObject(LOCK_INSTANCE_ID, instanceId));

      keys = new BasicDBObject();
      keys.put(CALENDAR_NAME, 1);
      calendarCollection.createIndex(keys, new IndexOptions().unique(true));

      try
      {
        // Drop the old indexes that were declared as name then group rather than group then name
        jobCollection.dropIndex("keyName_1_keyGroup_1");
        triggerCollection.dropIndex("keyName_1_keyGroup_1");
        locksCollection.dropIndex("keyName_1_keyGroup_1");
      }
      catch (MongoCommandException cfe)
      {
        // Ignore, the old indexes have already been removed
      }
    } catch(final MongoException e){
      throw new SchedulerConfigException("Error while initializing the indexes", e);
    }
  }

  protected void storeTrigger(OperableTrigger newTrigger, ObjectId jobId, boolean replaceExisting) throws JobPersistenceException {
    BasicDBObject trigger = new BasicDBObject();
    trigger.put(TRIGGER_STATE, STATE_WAITING);
    trigger.put(TRIGGER_CALENDAR_NAME, newTrigger.getCalendarName());
    trigger.put(TRIGGER_CLASS, newTrigger.getClass().getName());
    trigger.put(TRIGGER_DESCRIPTION, newTrigger.getDescription());
    trigger.put(TRIGGER_END_TIME, newTrigger.getEndTime());
    trigger.put(TRIGGER_FINAL_FIRE_TIME, newTrigger.getFinalFireTime());
    trigger.put(TRIGGER_FIRE_INSTANCE_ID, newTrigger.getFireInstanceId());
    trigger.put(TRIGGER_JOB_ID, jobId);
    trigger.put(KEY_NAME, newTrigger.getKey().getName());
    trigger.put(KEY_GROUP, newTrigger.getKey().getGroup());
    trigger.put(TRIGGER_MISFIRE_INSTRUCTION, newTrigger.getMisfireInstruction());
    trigger.put(TRIGGER_NEXT_FIRE_TIME, newTrigger.getNextFireTime());
    trigger.put(TRIGGER_PREVIOUS_FIRE_TIME, newTrigger.getPreviousFireTime());
    trigger.put(TRIGGER_PRIORITY, newTrigger.getPriority());
    trigger.put(TRIGGER_START_TIME, newTrigger.getStartTime());
    
    if (newTrigger.getJobDataMap().size() > 0) {
      try {
        String jobDataString = jobDataToString(newTrigger.getJobDataMap());
        trigger.put(JOB_DATA, jobDataString);
      } catch (IOException ioe) {
        throw new JobPersistenceException("Could not serialise job data map on the trigger for " + newTrigger.getKey(), ioe);
      }
    }
    
    TriggerPersistenceHelper tpd = triggerPersistenceDelegateFor(newTrigger);
    trigger = (BasicDBObject) tpd.injectExtraPropertiesForInsert(newTrigger, trigger);

    if (replaceExisting) {
      trigger.remove("_id");
      triggerCollection.updateMany(keyToDBObject(newTrigger.getKey()), trigger);
    } else {
      try {
        triggerCollection.insertOne(trigger);
      } catch (DuplicateKeyException key) {
        throw new ObjectAlreadyExistsException(newTrigger);
      }
    }
  }

  protected ObjectId storeJobInMongo(JobDetail newJob, boolean replaceExisting) throws ObjectAlreadyExistsException {
    JobKey key = newJob.getKey();

    BasicDBObject keyDbo = keyToDBObject(key);
    BasicDBObject job = keyToDBObject(key);

    job.put(KEY_NAME, key.getName());
    job.put(KEY_GROUP, key.getGroup());
    job.put(JOB_DESCRIPTION, newJob.getDescription());
    job.put(JOB_CLASS, newJob.getJobClass().getName());
    job.put(JOB_DURABILITY, newJob.isDurable());

    job.putAll(newJob.getJobDataMap());

    DBObject object = jobCollection.find(keyDbo).first();
    
    ObjectId objectId = null;
    
    if (object != null && replaceExisting) {
      jobCollection.updateOne(keyDbo, job);
    } else if (object == null) {
      try {
        jobCollection.insertOne(job);
        objectId = (ObjectId) job.get("_id");
      } catch (DuplicateKeyException e) {
        // Fine, find it and get its id.
        object = jobCollection.find(keyDbo).first();
        objectId = (ObjectId) object.get("_id");
      }
    } else {
      objectId = (ObjectId) object.get("_id");
    }

    return objectId;
  }

  protected void removeTriggerLock(OperableTrigger trigger) {
    log.info("Removing trigger lock {}.{}", trigger.getKey(), instanceId);
    BasicDBObject lock = new BasicDBObject();
    lock.put(KEY_NAME, trigger.getKey().getName());
    lock.put(KEY_GROUP, trigger.getKey().getGroup());

    // Comment this out, as expired trigger locks should be deleted by any another instance
    // lock.put(LOCK_INSTANCE_ID, instanceId);

    locksCollection.deleteMany(lock);
    log.info("Trigger lock {}.{} removed.", trigger.getKey(), instanceId);
  }

  protected ClassLoader getJobClassLoader() {
    return loadHelper.getClassLoader();
  }

  private JobDetail retrieveJob(OperableTrigger trigger) throws JobPersistenceException {
    try {
      return retrieveJob(trigger.getJobKey());
    } catch (JobPersistenceException e) {
      removeTriggerLock(trigger);
      throw e;
    }
  }

  protected DBObject findJobDocumentByKey(JobKey key) {
    return jobCollection.find(keyToDBObject(key)).first();
  }

  protected DBObject findTriggerDocumentByKey(TriggerKey key) {
    return triggerCollection.find(keyToDBObject(key)).first();
  }

  private void initializeHelpers() {
    this.persistenceHelpers = new ArrayList<TriggerPersistenceHelper>();

    persistenceHelpers.add(new SimpleTriggerPersistenceHelper());
    persistenceHelpers.add(new CalendarIntervalTriggerPersistenceHelper());
    persistenceHelpers.add(new CronTriggerPersistenceHelper());
    persistenceHelpers.add(new DailyTimeIntervalTriggerPersistenceHelper());

    this.queryHelper = new QueryHelper();
  }

  private TriggerState triggerStateForValue(String ts) {
    if (ts == null) {
      return TriggerState.NONE;
    }

    if (ts.equals(STATE_DELETED)) {
      return TriggerState.NONE;
    }

    if (ts.equals(STATE_COMPLETE)) {
      return TriggerState.COMPLETE;
    }

    if (ts.equals(STATE_PAUSED)) {
      return TriggerState.PAUSED;
    }

    if (ts.equals(STATE_PAUSED_BLOCKED)) {
      return TriggerState.PAUSED;
    }

    if (ts.equals(STATE_ERROR)) {
      return TriggerState.ERROR;
    }

    if (ts.equals(STATE_BLOCKED)) {
      return TriggerState.BLOCKED;
    }

    // waiting or acquired
    return TriggerState.NORMAL;
  }

  private BasicDBObject updateThatSetsTriggerStateTo(String state) {
    //TODO implement using Filters
    return (BasicDBObject) BasicDBObjectBuilder.
        start("$set", new BasicDBObject(TRIGGER_STATE, state)).
        get();
  }

  private void markTriggerGroupsAsPaused(Collection<String> groups) {
    List<BasicDBObject> list = new ArrayList<BasicDBObject>();
    for (String s : groups) {
      list.add(new BasicDBObject(KEY_GROUP, s));
    }
    pausedTriggerGroupsCollection.insertMany(list);
  }

  private void unmarkTriggerGroupsAsPaused(Collection<String> groups) {
    pausedTriggerGroupsCollection.deleteMany(Filters.in(KEY_GROUP, groups));
  }

  private void markJobGroupsAsPaused(List<String> groups) {
    if (groups == null) {
      throw new IllegalArgumentException("groups cannot be null!");
    }
    List<BasicDBObject> list = new ArrayList<BasicDBObject>();
    for (String s : groups) {
      list.add(new BasicDBObject(KEY_GROUP, s));
    }
    pausedJobGroupsCollection.insertMany(list);
  }

  private void unmarkJobGroupsAsPaused(Collection<String> groups) {
    pausedJobGroupsCollection.deleteMany(Filters.in(KEY_GROUP, groups));
  }


  private Collection<ObjectId> idsFrom(Collection<DBObject> docs) {
    // so much repetitive code would be gone if Java collections just had .map and .filter…
    List<ObjectId> list = new ArrayList<ObjectId>();
    for (DBObject doc : docs) {
      list.add((ObjectId) doc.get("_id"));
    }
    return list;
  }

  private Collection<DBObject> findJobDocumentsThatMatch(GroupMatcher<JobKey> matcher) {
    final GroupHelper groupHelper = new GroupHelper(jobCollection, queryHelper);
    return groupHelper.inGroupsThatMatch(matcher);
  }
  
  protected void jobDataMapFromString(JobDataMap jobDataMap, String clob)
    throws IOException {
      
    try {
      byte[] bytes = Base64.decodeBase64(clob);
        
      Map<String, ?> map = stringMapFromBytes(bytes);
        
      jobDataMap.putAll(map);
      jobDataMap.clearDirtyFlag();
        
    } catch (NotSerializableException e) {
      throw new NotSerializableException(
        "Unable to serialize JobDataMap for insertion into " + 
        "database because the value of property '" + 
        getKeyOfNonSerializableStringMapEntry(jobDataMap.getWrappedMap()) + 
        "' is not serializable: " + e.getMessage());
    }
    catch (ClassNotFoundException e)
    {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }
  
  private Map<String, ?> stringMapFromBytes(byte[] bytes) throws IOException, ClassNotFoundException
  {
    ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
    ObjectInputStream ois = new ObjectInputStream(bais);
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) ois.readObject();
    ois.close();
    
    return map;
  }

  protected String jobDataToString(JobDataMap jobDataMap)
      throws IOException {
      
    try {
      byte[] bytes = stringMapToBytes(jobDataMap.getWrappedMap());
      return Base64.encodeBase64String(bytes);
    } catch (NotSerializableException e) {
      throw new NotSerializableException(
        "Unable to serialize JobDataMap for insertion into " + 
        "database because the value of property '" +
        getKeyOfNonSerializableStringMapEntry(jobDataMap.getWrappedMap()) +
        "' is not serializable: " + e.getMessage());
    }
  }
  
  private byte[] stringMapToBytes(Object object) throws IOException
  {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ObjectOutputStream out = new ObjectOutputStream(baos);
    out.writeObject(object);
    out.flush();
    
    return baos.toByteArray();
  }

  private String getKeyOfNonSerializableStringMapEntry(Map<String, ?> data) {
    
    for (Map.Entry<String, ?> entry : data.entrySet()) {

      ByteArrayOutputStream baos = new ByteArrayOutputStream();

      try {
        ObjectOutputStream out = new ObjectOutputStream(baos);
        out.writeObject(entry.getValue());
        out.flush();
      } 
      catch (IOException e) {
        return entry.getKey();
      }
    }
      
    return null;   
  }
  
  public void setMongoOptionMaxConnectionsPerHost(int maxConnectionsPerHost) {
    this.mongoOptionMaxConnectionsPerHost = maxConnectionsPerHost;
  }

  public void setMongoOptionConnectTimeoutMillis(int maxConnectWaitTime) {
    this.mongoOptionConnectTimeoutMillis = maxConnectWaitTime;
  }

  public void setMongoOptionSocketTimeoutMillis(int socketTimeoutMillis) {
    this.mongoOptionSocketTimeoutMillis = socketTimeoutMillis;
  }

  public void setMongoOptionThreadsAllowedToBlockForConnectionMultiplier(int threadsAllowedToBlockForConnectionMultiplier) {
    this.mongoOptionThreadsAllowedToBlockForConnectionMultiplier = threadsAllowedToBlockForConnectionMultiplier;
  }

  public void setMongoOptionSocketKeepAlive(boolean mongoOptionSocketKeepAlive) {
    this.mongoOptionSocketKeepAlive = mongoOptionSocketKeepAlive;
  }

  public String getAuthDbName() {
      return authDbName;
  }

  public void setAuthDbName(String authDbName) {
      this.authDbName = authDbName;
  }
}
