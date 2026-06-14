package com.gcll.ticketagent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gcll.ticketagent.persistence.entity.PendingActionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PendingActionMapper extends BaseMapper<PendingActionEntity> {
}
