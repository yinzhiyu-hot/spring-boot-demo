package cn.wangoon.cache;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.wangoon.common.annotations.CronExpression;
import cn.wangoon.common.annotations.ShardingItemParams;
import cn.wangoon.common.annotations.ShardingTotalCount;
import cn.wangoon.common.cache.BaseCache;
import cn.wangoon.config.JobsConfig;
import cn.wangoon.constants.JobsConstants;
import cn.wangoon.common.constants.RedisConstants;
import cn.wangoon.common.enums.JobStatusEnum;
import cn.wangoon.common.utils.*;
import cn.wangoon.domain.entity.SysJobConfig;
import cn.wangoon.domain.entity.SysLog;
import cn.wangoon.service.business.base.SysLogService;
import cn.wangoon.service.business.base.SysJobConfigService;
import cn.wangoon.job.BaseDataflowJob;
import cn.wangoon.job.BaseSimpleJob;
import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;
import com.dangdang.ddframe.job.api.ElasticJob;
import com.dangdang.ddframe.job.lite.api.JobScheduler;
import com.google.common.collect.Maps;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @Description 系统启动中加载jobs配置, 且初始化job
 * @PackagePath cn.wangoon.service.cache.impl.JobsConfigCache
 * @Author YINZHIYU
 * @Date 2020-04-13 09:55:00
 * @Version 1.0.0.0
 **/
@Component
public class JobsConfigCache implements BaseCache {

    @Resource
    JobsConfig jobsConfig;

    @Resource
    SysJobConfigService sysJobConfigService;

    @Resource
    private RedisUtils redisUtils;

    @Resource
    private SysLogService sysLogService;

    /**
     * Job 调度map，便于管理启停
     * KEY -> BeanName  Value JobScheduler
     */
    public final static ConcurrentMap<String, JobScheduler> jobSchedulerMap = Maps.newConcurrentMap();
    /**
     * Job 调度配置map，便于管理状态
     * KEY -> BeanName  Value SysJobConfig
     */
    public final static ConcurrentMap<String, SysJobConfig> sysJobConfigMap = Maps.newConcurrentMap();

    @Override
    public void init() {
        LogUtils.info("加载JobSchedulerMap");

        startJobs();
    }

    /*
     * @Description 添加job
     * @Params ==>
     * @Param jobClassBeanName       job bean名称
     * @Param cron                   表达式
     * @Param shardingTotalCount     分片数
     * @Param shardingItemParameters 分片参数
     * @Return
     * @Date 2020/4/13 11:14
     * @Auther YINZHIYU
     */
    private JobScheduler createJobScheduler(String jobClassBeanName, String cron, int shardingTotalCount, String shardingItemParameters) {

        JobScheduler jobScheduler = null;
        try {
            ElasticJob job = CastUtil.cast(SpringBootBeanUtil.getBean(jobClassBeanName));
            if (ObjectUtil.isEmpty(job)) {
                LogUtils.error(String.format("创建Job ==> 获取Job Bean 失败 ==> SpringBoot 并没有配置名为 %s 的Bean", jobClassBeanName));
                return jobScheduler;
            }
            jobScheduler = jobsConfig.addJobScheduler(job, cron, shardingTotalCount, shardingItemParameters);
        } catch (Exception e) {
            LogUtils.error("创建Job异常", e);
        }
        return jobScheduler;
    }

    /*
     * @Description 启动指定包路径下的定时任务
     * @Params ==>
     * @Param scanPackage 包路径
     * @Return void
     * @Date 2020/6/16 18:00
     * @Auther YINZHIYU
     */
    private void startJobs() {
        try {

            //获取所有定义的job
            Map<String, Class<?>> stringClassMap = Maps.newHashMap();
            stringClassMap.putAll(ClassUtils.getSuperClassMapForComponent(BaseSimpleJob.class));//获取简单处理job
            stringClassMap.putAll(ClassUtils.getSuperClassMapForComponent(BaseDataflowJob.class));//获取流式处理job

            //获取数据库已经配置的job
            List<SysJobConfig> sysJobConfigs = sysJobConfigService.list();

            //筛选出数据库中的job配置在程序中不存在的job
            List<String> jobNamesForPorgram = new ArrayList<>(stringClassMap.keySet());
            List<SysJobConfig> deleteJobConfigs = sysJobConfigs.stream().filter(job -> !jobNamesForPorgram.contains(job.getJobClassBeanName())).collect(Collectors.toList());
            sysJobConfigs = sysJobConfigs.stream().filter(job -> jobNamesForPorgram.contains(job.getJobClassBeanName())).collect(Collectors.toList());

            Map<String, SysJobConfig> jobConfigMap = ObjectUtil.isNotEmpty(sysJobConfigs) ? sysJobConfigs.stream().collect(Collectors.toMap(SysJobConfig::getJobClassBeanName, Function.identity(), (v1, v2) -> v1)) : Maps.newHashMap();

            //遍历所有定义的job
            for (Map.Entry<String, Class<?>> stringClassEntry : stringClassMap.entrySet()) {

                //判断数据库是否配置job
                SysJobConfig sysJobConfig = jobConfigMap.get(stringClassEntry.getKey());

                //若无配置，则配置
                if (ObjectUtil.isEmpty(sysJobConfig)) {
                    Description description = stringClassEntry.getValue().getAnnotation(Description.class);
                    ShardingItemParams shardingItemParams = stringClassEntry.getValue().getAnnotation(ShardingItemParams.class);
                    ShardingTotalCount shardingTotalCount = stringClassEntry.getValue().getAnnotation(ShardingTotalCount.class);
                    CronExpression cronExpression = stringClassEntry.getValue().getAnnotation(CronExpression.class);

                    //新增job的数据库配置
                    sysJobConfig = new SysJobConfig();
                    sysJobConfig.setJobName(stringClassEntry.getKey());
                    sysJobConfig.setJobClassBeanName(stringClassEntry.getKey());
                    sysJobConfig.setShardingTotalCount(ObjectUtil.isNotEmpty(shardingTotalCount) ? shardingTotalCount.value() : 1);
                    sysJobConfig.setShardingItemParams(ObjectUtil.isNotEmpty(shardingItemParams) ? shardingItemParams.value() : "系统自动填入，Job中未配置分片参数注解，请自行添加分片参数");
                    sysJobConfig.setCronExpression(ObjectUtil.isNotEmpty(cronExpression) ? cronExpression.value() : JobsConstants.DEFAULT_CRON_EXPRESSION);
                    sysJobConfig.setRemark(ObjectUtil.isNotEmpty(description) ? description.value() : "系统自动填入，Job中未配置备注描述注解，请自行添加备注");

                    if (StrUtil.equals(JobsConstants.SYSTEM_LISTENER_JOB_CLASS_BEAN_NAME, stringClassEntry.getKey())) {
                        sysJobConfig.setJobStatus(JobStatusEnum.START.getStatus());
                    } else {
                        sysJobConfig.setJobStatus(JobStatusEnum.STOP.getStatus());
                    }

                    sysJobConfig.setCreateUser("Sys-Auto");
                    sysJobConfig.setCreateTime(DateUtil.date());
                    sysJobConfig.setUpdateUser("Sys-Auto");
                    sysJobConfig.setUpdateTime(DateUtil.date());

                    //数据库新增job配置
                    boolean insertResult = SqlHelper.retBool(sysJobConfigService.getBaseMapper().insert(sysJobConfig));
                    if (insertResult) {
                        sysJobConfigs.add(sysJobConfig);//如果是程序新增的JOB，则此处加到内存里，减少再查一次数据库
                    }
                }
                try {
                    //根据配置状态启动Job
                    JobScheduler jobScheduler = createJobScheduler(sysJobConfig.getJobClassBeanName(), sysJobConfig.getCronExpression(), sysJobConfig.getShardingTotalCount(), sysJobConfig.getShardingItemParams());
                    if (ObjectUtil.isNotEmpty(jobScheduler)) {
                        jobScheduler.init();
                        jobScheduler.getSchedulerFacade().registerStartUpInfo(JobStatusEnum.getStart(sysJobConfig.getJobStatus()));//是否启动
                        jobSchedulerMap.put(sysJobConfig.getJobClassBeanName(), jobScheduler);//便于管理JOB启停
                        sysJobConfigMap.put(sysJobConfig.getJobClassBeanName(), sysJobConfig);//便于管理JOB状态
                    }
                } catch (Exception e) {
                    sysLogService.recordLog(new SysLog("startJobs", String.format("系统启动时启停定时任务 %s 异常 ==> %s。", sysJobConfig.getJobClassBeanName(), e)));
                }
            }

            //全部初始化好后，再检查并删除需要废弃的Zk中的Job节点(删除数据库需要废弃的节点，在此同步删除Zk中的节点)
            List<String> zkNodes = jobsConfig.getChildNodes("/");//Zk中系统下的所有JOB节点
            List<String> dbNodes = sysJobConfigs.stream().map(SysJobConfig::getJobClassBeanName).collect(Collectors.toList());//数据存在的节点
            List<String> zkDeleteNodes = zkNodes.stream().filter(node -> {
                String temp = node.substring(node.lastIndexOf(".") + 1);
                return !dbNodes.contains(temp);
            }).collect(Collectors.toList());//过滤出在数据库没有的节点
            zkDeleteNodes.forEach(nodePath -> jobsConfig.deleteNode(nodePath));

            //全部初始化后，删除数据库中无效的job
            if (ObjectUtil.isNotEmpty(deleteJobConfigs)) {
                sysJobConfigService.removeByIds(deleteJobConfigs.stream().map(SysJobConfig::getId).collect(Collectors.toList()));
            }

            try {
                redisUtils.set(RedisConstants.SYS_JOB_CONFIG_MAP_KEY, sysJobConfigMap);
            } catch (Exception e) {
                LogUtils.error(RedisConstants.SYS_JOB_CONFIG_MAP_KEY, sysJobConfigMap, "redisUtils.set(RedisConstants.SYS_JOB_CONFIG_MAP_KEY, sysJobConfigMap) ==> 操作Redis ==> 异常", e);
            }

        } catch (Exception e) {
            sysLogService.recordLog(new SysLog("startJobs", String.format("加载启动定时任务异常 ==> %s。", e)));
        }
    }
}
