package com.gcll.ticketagent.persistence.repository;

import com.gcll.ticketagent.persistence.entity.OpsLogSampleEntity;
import com.gcll.ticketagent.persistence.entity.OpsMetricSampleEntity;

import java.util.List;

public interface OpsSampleRepository {

    List<OpsLogSampleEntity> searchLogs(String systemName, String moduleName, String query, int limit);

    List<OpsMetricSampleEntity> searchMetrics(String systemName, String moduleName, String query, int limit);
}
