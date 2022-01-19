/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.engine.master.jobdealer;

import com.dtstack.engine.domain.EngineJobCache;
import com.dtstack.engine.domain.ScheduleJob;
import com.dtstack.engine.mapper.EngineJobRetryMapper;
import com.dtstack.engine.master.jobdealer.bo.EngineJobRetry;
import com.dtstack.engine.master.jobdealer.cache.ShardCache;
import com.dtstack.engine.master.service.EngineJobCacheService;
import com.dtstack.engine.master.service.ScheduleJobService;
import com.dtstack.engine.pluginapi.JobClient;
import com.dtstack.engine.pluginapi.enums.EJobType;
import com.dtstack.engine.pluginapi.enums.EngineType;
import com.dtstack.engine.pluginapi.enums.RdosTaskStatus;
import com.dtstack.engine.pluginapi.pojo.ParamAction;
import com.dtstack.engine.pluginapi.util.PublicUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;


/**
 * 注意如果是由于资源不足导致的任务失败应该减慢发送速度
 * Date: 2018/3/22
 * Company: www.dtstack.com
 * @author xuchao
 */
@Component
public class JobRestartDealer {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobRestartDealer.class);

    @Autowired
    private EngineJobCacheService engineJobCacheService;

    @Autowired
    private ScheduleJobService scheduleJobService;

    @Autowired
    private EngineJobRetryMapper engineJobRetryMapper;

    @Autowired
    private ShardCache shardCache;

    @Autowired
    private JobDealer jobDealer;

    /**
     * 对提交结果判定是否重试
     * 不限制重试次数
     * @param jobClient
     * @return
     */
    public boolean checkAndRestartForSubmitResult(JobClient jobClient){
        if(!checkSubmitResult(jobClient)){
            return false;
        }

        int alreadyRetryNum = getAlreadyRetryNum(jobClient.getJobId());
        if (alreadyRetryNum >= jobClient.getMaxRetryNum()) {
            LOGGER.info("[retry=false] jobId:{} alreadyRetryNum:{} maxRetryNum:{}, alreadyRetryNum >= maxRetryNum.", jobClient.getJobId(), alreadyRetryNum, jobClient.getMaxRetryNum());
            return false;
        }

        boolean retry = restartJob(jobClient);
        LOGGER.info("【retry={}】 jobId:{} alreadyRetryNum:{} will retry and add into queue again.", retry, jobClient.getJobId(), alreadyRetryNum);

        return retry;
    }

    private boolean checkSubmitResult(JobClient jobClient){
        if(jobClient.getJobResult() == null){
            //未提交过
            return true;
        }

        if(!jobClient.getJobResult().getCheckRetry()){
            LOGGER.info("[retry=false] jobId:{} jobResult.checkRetry:{} jobResult.msgInfo:{} check retry is false.", jobClient.getJobId(), jobClient.getJobResult().getCheckRetry(), jobClient.getJobResult().getMsgInfo());
            return false;
        }

        if(!jobClient.getIsFailRetry()){
            LOGGER.info("[retry=false] jobId:{} isFailRetry:{} isFailRetry is false.", jobClient.getJobId(), jobClient.getIsFailRetry());
            return false;
        }

        return true;
    }

    /***
     * 对任务状态判断是否需要重试
     * @param status
     * @param scheduleJob
     * @param jobCache
     * @return
     */
    public boolean checkAndRestart(Integer status, ScheduleJob scheduleJob,EngineJobCache jobCache){
        Pair<Boolean, JobClient> checkResult = checkJobInfo(scheduleJob.getJobId(), jobCache, status);
        if(!checkResult.getKey()){
            return false;
        }


        JobClient jobClient = checkResult.getValue();
        // 是否需要重新提交
        int alreadyRetryNum = getAlreadyRetryNum(scheduleJob.getJobId());
        if (alreadyRetryNum >= jobClient.getMaxRetryNum()) {
            LOGGER.info("[retry=false] jobId:{} alreadyRetryNum:{} maxRetryNum:{}, alreadyRetryNum >= maxRetryNum.", jobClient.getJobId(), alreadyRetryNum, jobClient.getMaxRetryNum());
            return false;
        }

        // 通过engineJobId或appId获取日志
        jobClient.setEngineTaskId(scheduleJob.getEngineJobId());
        jobClient.setApplicationId(scheduleJob.getApplicationId());

        jobClient.setCallBack((jobStatus)->{
            updateJobStatus(scheduleJob.getJobId(), jobStatus);
        });

        if(EngineType.Kylin.name().equalsIgnoreCase(jobClient.getEngineType())){
            setRetryTag(jobClient);
        }

        //checkpoint的路径
        if(EJobType.SYNC.equals(jobClient.getJobType())){
            setCheckpointPath(jobClient);
        }

        boolean retry = restartJob(jobClient);
        LOGGER.info("【retry={}】 jobId:{} alreadyRetryNum:{} will retry and add into queue again.", retry, jobClient.getJobId(), alreadyRetryNum);

        return retry;
    }

    private void setRetryTag(JobClient jobClient){
        try {
            Map<String, Object> pluginInfoMap = PublicUtil.jsonStrToObject(jobClient.getPluginInfo(), Map.class);
            pluginInfoMap.put("retry", true);
            jobClient.setPluginInfo(PublicUtil.objToString(pluginInfoMap));
        } catch (IOException e) {
            LOGGER.error("Set retry tag error:", e);
        }
    }

    /**
     * 设置这次实例重试的checkpoint path为上次任务实例生成的最后一个checkpoint path
     * @param jobClient client
     */
    private void setCheckpointPath(JobClient jobClient){

        String checkpoint = jobClient.getConfProperties().getProperty("openCheckpoint");
        boolean openCheckpoint = checkpoint != null && Boolean.parseBoolean(checkpoint.trim());
        if (!openCheckpoint){
            return;
        }

        if(StringUtils.isEmpty(jobClient.getJobId())){
            return;
        }

      /*  EngineJobCheckpoint taskCheckpoint = engineJobCheckpointDao.getByTaskId(jobClient.getJobId());
        if(taskCheckpoint != null){
            jobClient.setExternalPath(taskCheckpoint.getCheckpointSavepath());
        }*/
        LOGGER.info("jobId:{} set checkpoint path:{}", jobClient.getJobId(), jobClient.getExternalPath());
    }

    private Pair<Boolean, JobClient> checkJobInfo(String jobId, EngineJobCache jobCache, Integer status) {
        Pair<Boolean, JobClient> check = new Pair<>(false, null);

        if(!RdosTaskStatus.FAILED.getStatus().equals(status) && !RdosTaskStatus.SUBMITFAILD.getStatus().equals(status)){
            return check;
        }

        try {
            String jobInfo = jobCache.getJobInfo();
            ParamAction paramAction = PublicUtil.jsonStrToObject(jobInfo, ParamAction.class);
            JobClient jobClient = new JobClient(paramAction);

            if(!jobClient.getIsFailRetry()){
                LOGGER.info("[retry=false] jobId:{} isFailRetry:{} isFailRetry is false.", jobClient.getJobId(), jobClient.getIsFailRetry());
                return check;
            }

            return new Pair<>(true, jobClient);
        } catch (Exception e){
            // 解析任务的jobInfo反序列到ParamAction失败，任务不进行重试.
            LOGGER.error("[retry=false] jobId:{} default not retry, because getIsFailRetry happens error:.", jobId, e);
            return check;
        }
    }

    private boolean restartJob(JobClient jobClient){
        EngineJobCache jobCache = engineJobCacheService.getByJobId(jobClient.getJobId());
        if (jobCache == null) {
            LOGGER.info("jobId:{} restart but jobCache is null.", jobClient.getJobId());
            return false;
        }
        String jobInfo = jobCache.getJobInfo();
        try {
            ParamAction paramAction = PublicUtil.jsonStrToObject(jobInfo, ParamAction.class);
            jobClient.setSql(paramAction.getSqlText());
        } catch (IOException e) {
            LOGGER.error("jobId:{} restart but convert paramAction error: ", jobClient.getJobId(), e);
            return false;
        }

        //添加到重试队列中
        boolean isAdd = jobDealer.addRestartJob(jobClient);
        if (isAdd) {
            String jobId = jobClient.getJobId();
            //重试任务更改在zk的状态，统一做状态清理
            shardCache.updateLocalMemTaskStatus(jobId, RdosTaskStatus.RESTARTING.getStatus());

            //重试的任务不置为失败，waitengine
            jobRetryRecord(jobClient);

            scheduleJobService.updateStatus(jobId,RdosTaskStatus.RESTARTING.getStatus());
            LOGGER.info("jobId:{} update job status:{}.", jobId, RdosTaskStatus.RESTARTING.getStatus());

            //update retryNum
            increaseJobRetryNum(jobClient.getJobId());
        }
        return isAdd;
    }

    private void jobRetryRecord(JobClient jobClient) {
        try {
            ScheduleJob batchJob = scheduleJobService.getByJobId(jobClient.getJobId());
            EngineJobRetry batchJobRetry = EngineJobRetry.toEntity(batchJob, jobClient);
            batchJobRetry.setStatus(RdosTaskStatus.RESTARTING.getStatus());
            engineJobRetryMapper.insert(batchJobRetry);
        } catch (Throwable e ){
            LOGGER.error("",e);
        }
    }

    private void updateJobStatus(String jobId, Integer status) {
        scheduleJobService.updateStatus(jobId, status);
        LOGGER.info("jobId:{} update job status:{}.", jobId, status);
    }

    /**
     * 获取任务已经重试的次数
     */
    private Integer getAlreadyRetryNum(String jobId){
        ScheduleJob rdosEngineBatchJob = scheduleJobService.getByJobId(jobId);
        return rdosEngineBatchJob == null || rdosEngineBatchJob.getRetryNum() == null ? 0 : rdosEngineBatchJob.getRetryNum();
    }

    private void increaseJobRetryNum(String jobId){
        ScheduleJob scheduleJob = scheduleJobService.getByJobId(jobId);
        if (scheduleJob == null) {
            return;
        }
        Integer retryNum = scheduleJob.getRetryNum() == null ? 0 : scheduleJob.getRetryNum();
        retryNum++;
        scheduleJobService.updateRetryNum(jobId, retryNum);
    }
}