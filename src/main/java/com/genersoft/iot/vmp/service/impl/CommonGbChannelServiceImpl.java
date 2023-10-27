package com.genersoft.iot.vmp.service.impl;

import com.genersoft.iot.vmp.common.CivilCodePo;
import com.genersoft.iot.vmp.common.CommonGbChannel;
import com.genersoft.iot.vmp.conf.CivilCodeFileConf;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.bean.Gb28181CodeType;
import com.genersoft.iot.vmp.gb28181.utils.SipUtils;
import com.genersoft.iot.vmp.service.ICommonGbChannelService;
import com.genersoft.iot.vmp.service.bean.Group;
import com.genersoft.iot.vmp.service.bean.CommonGbChannelType;
import com.genersoft.iot.vmp.service.bean.Region;
import com.genersoft.iot.vmp.storager.dao.CommonGbChannelMapper;
import com.genersoft.iot.vmp.storager.dao.DeviceChannelMapper;
import com.genersoft.iot.vmp.storager.dao.GroupMapper;
import com.genersoft.iot.vmp.storager.dao.RegionMapper;
import com.genersoft.iot.vmp.utils.DateUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.*;

@Service
public class CommonGbChannelServiceImpl implements ICommonGbChannelService {

    private final static Logger logger = LoggerFactory.getLogger(CommonGbChannelServiceImpl.class);

    @Autowired
    private CommonGbChannelMapper commonGbChannelMapper;

    @Autowired
    private DeviceChannelMapper deviceChannelMapper;

    @Autowired
    private GroupMapper groupMapper;

    @Autowired
    private RegionMapper regionMapper;

    @Autowired
    private DataSourceTransactionManager dataSourceTransactionManager;

    @Autowired
    private TransactionDefinition transactionDefinition;

    @Autowired
    private CivilCodeFileConf civilCodeFileConf;


    @Override
    public CommonGbChannel getChannel(String channelId) {
        return commonGbChannelMapper.queryByDeviceID(channelId);
    }

    @Override
    public int add(CommonGbChannel channel) {
        return commonGbChannelMapper.add(channel);
    }

    @Override
    public int addFromGbChannel(DeviceChannel channel) {
        CommonGbChannel commonGbChannel = commonGbChannelMapper.queryByDeviceID(channel.getChannelId());
        logger.info("[添加通用通道]来自国标通道，国标编号: {}, 同步所有字段", channel.getChannelId());
        if (commonGbChannel != null) {
            logger.info("[添加通用通道]来自国标通道，失败，已存在。国标编号: {}", channel.getChannelId());
            return 0;
        }
        CommonGbChannel commonChannelFromDeviceChannel = getCommonChannelFromDeviceChannel(channel, null);
        return commonGbChannelMapper.add(commonChannelFromDeviceChannel);
    }

    @Override
    public int delete(String channelId) {
        return commonGbChannelMapper.deleteByDeviceID(channelId);
    }

    @Override
    public int update(CommonGbChannel channel) {
        return commonGbChannelMapper.update(channel);
    }

    @Override
    public boolean checkChannelInPlatform(String channelId, String platformServerId) {
        return commonGbChannelMapper.checkChannelInPlatform(channelId, platformServerId) > 0;
    }

    @Override
    public boolean syncChannelFromGb28181Device(String gbDeviceId, List<String> syncKeys, Boolean syncGroup, Boolean syncRegion) {
        logger.info("[同步通用通道]来自国标设备，国标编号: {}", gbDeviceId);
        List<DeviceChannel> deviceChannels = deviceChannelMapper.queryAllChannels(gbDeviceId);
        if (deviceChannels.isEmpty()) {
            logger.info("[同步通用通道]来自国标设备，结束， 通道数为0, 国标编号: {}", gbDeviceId);
            return false;
        }
        List<CommonGbChannel> commonGbChannelList = new ArrayList<>();
        // 存储得到的10到13位为215的业务分组数据
        Map<String, Group> businessGroupMap = new HashMap<>();
        // 存储得到的10到13位为216的虚拟组织 数据
        Map<String, Group> virtuallyGroupMap = new HashMap<>();
        // 存储得到的行政区划数据
        Map<String, Region> regionMap = new HashMap<>();
//        // 存储得到的所有parentId, 后续检验parentId是否已传输对应的分组/行政区划数据，从而确定是否需要自动创建节点。
//        Set<String> parentIdSet = new HashSet<>();
        // 存储得到的所有行政区划, 后续检验civilCode是否已传输对应的行政区划数据，从而确定是否需要自动创建节点。
        Set<String> civilCodeSet = new HashSet<>();
        List<String> clearChannels = new ArrayList<>();
        // 对数据进行分类
        deviceChannels.stream().forEach(deviceChannel -> {
            if (deviceChannel.getCommonGbChannelId() > 0) {
                clearChannels.add(deviceChannel.getChannelId());
            }
            Gb28181CodeType channelIdType = SipUtils.getChannelIdType(deviceChannel.getChannelId());
            if (channelIdType != null) {
                if (
                    (
                        channelIdType == Gb28181CodeType.CIVIL_CODE_PROVINCE
                            || channelIdType == Gb28181CodeType.CIVIL_CODE_CITY
                            || channelIdType == Gb28181CodeType.CIVIL_CODE_COUNTY
                            || channelIdType == Gb28181CodeType.CIVIL_CODE_GRASS_ROOTS
                    )
                    &&
                    !regionMap.containsKey(deviceChannel.getChannelId())
                ) {
                    CivilCodePo parentCivilCodePo = civilCodeFileConf.getParentCode(deviceChannel.getChannelId());
                    String civilCode = null;
                    if (parentCivilCodePo != null) {
                        civilCode = parentCivilCodePo.getCode();
                    }
                    // 行政区划条目
                    Region region = Region.getInstance(deviceChannel.getChannelId(), deviceChannel.getName(),
                            civilCode);
                    regionMap.put(deviceChannel.getChannelId(), region);
                }
                if (channelIdType == Gb28181CodeType.BUSINESS_GROUP
                        && !businessGroupMap.containsKey(deviceChannel.getChannelId())) {
                    Group group = Group.getInstance(deviceChannel.getChannelId(), deviceChannel.getName(),
                            null, deviceChannel.getChannelId());
                    businessGroupMap.put(deviceChannel.getChannelId(), group);
                }
                if (channelIdType == Gb28181CodeType.VIRTUAL_ORGANIZATION
                        && !virtuallyGroupMap.containsKey(deviceChannel.getChannelId())) {
                    Group group = Group.getInstance(deviceChannel.getChannelId(), deviceChannel.getName(), deviceChannel.getParentId(), deviceChannel.getBusinessGroupId());
                    virtuallyGroupMap.put(deviceChannel.getChannelId(), group);
                }
            }else {
                if (!StringUtils.isEmpty(deviceChannel.getCivilCode())) {
                    civilCodeSet.add(deviceChannel.getCivilCode());
                }
                CommonGbChannel commonGbChannel = getCommonChannelFromDeviceChannel(deviceChannel, syncKeys);
                commonGbChannelList.add(commonGbChannel);
            }
        });
        // 检查是否存在已存在通道与将写入通道相同的情况
        List<CommonGbChannel> commonGbChannelInDbList = commonGbChannelMapper.queryInList(commonGbChannelList);
        if (!commonGbChannelInDbList.isEmpty()) {
            // 这里可以控制新数据覆盖旧数据还是丢弃重复的新数据
            // 目前使用新数据覆盖旧数据，后续分局实际业务需求再做修改
            commonGbChannelInDbList.stream().forEach(commonGbChannel->{
                clearChannels.add(commonGbChannel.getCommonGbDeviceID());
            });
        }


        // 检测分组境况
        if (businessGroupMap.isEmpty()) {
            virtuallyGroupMap.clear();
        }else {
            // 检查业务分组与虚拟组织
            if (!virtuallyGroupMap.isEmpty()) {
                for (String key : virtuallyGroupMap.keySet()) {
                    Group virtuallyGroup = virtuallyGroupMap.get(key);
                    if (virtuallyGroup.getCommonGroupTopId() == null
                            || !businessGroupMap.containsKey(virtuallyGroup.getCommonGroupTopId())
                    ) {
                        virtuallyGroupMap.remove(key);
                        continue;
                    }
                    if (virtuallyGroup.getCommonGroupParentId() != null && !virtuallyGroupMap.containsKey(virtuallyGroup.getCommonGroupParentId())) {
                        virtuallyGroup.setCommonGroupParentId(null);
                    }
                }
                if (virtuallyGroupMap.isEmpty()) {
                    businessGroupMap.clear();
                }
            }
        }
        // 检测行政区划信息是否完整
        for (String civilCode : civilCodeSet) {
            if (!regionMap.containsKey(civilCode)) {
                logger.warn("[通道信息中缺少地区信息]补充地区信息 国标编号: {}， civilCode： {}", gbDeviceId, civilCode );
                Region region = civilCodeFileConf.createRegion(civilCode);
                if (region != null) {
                    regionMap.put(region.getCommonRegionDeviceId(), region);
                }else {
                    logger.warn("[获取地区信息]失败 国标编号: {}， civilCode： {}", gbDeviceId, civilCode );
                }
            }
        }
        // 对待写入的数据做处理
        if (!commonGbChannelList.isEmpty()) {
            commonGbChannelList.stream().forEach(commonGbChannel -> {
                if (commonGbChannel.getCommonGbParentID() != null
                        && !virtuallyGroupMap.containsKey(commonGbChannel.getCommonGbParentID())) {
                    commonGbChannel.setCommonGbParentID(null);
                }
                if (commonGbChannel.getCommonGbBusinessGroupID() != null
                        && !businessGroupMap.containsKey(commonGbChannel.getCommonGbBusinessGroupID())) {
                    commonGbChannel.setCommonGbBusinessGroupID(null);
                }
                if (commonGbChannel.getCommonGbCivilCode() != null
                    && !regionMap.containsKey(commonGbChannel.getCommonGbCivilCode())) {
                    commonGbChannel.setCommonGbCivilCode(null);
                }
            });
        }
        // ====开始写入数据====
        // 清理重复数据
        TransactionStatus transactionStatus = dataSourceTransactionManager.getTransaction(transactionDefinition);
        int limit = 50;
        if (!clearChannels.isEmpty()) {
            if (clearChannels.size() <= limit) {
                commonGbChannelMapper.deleteByDeviceIDs(clearChannels);
            } else {
                for (int i = 0; i < clearChannels.size(); i += limit) {
                    int toIndex = i + limit;
                    if (i + limit > clearChannels.size()) {
                        toIndex = clearChannels.size();
                    }
                    List<String> clearChannelsSun = clearChannels.subList(i, toIndex);
                    int currentResult = commonGbChannelMapper.deleteByDeviceIDs(clearChannelsSun);
                    if (currentResult <= 0) {
                        dataSourceTransactionManager.rollback(transactionStatus);
                        return false;
                    }
                }
            }
        }
        // 写入通道数据
        boolean result;
        if (commonGbChannelList.size() <= limit) {
            result = commonGbChannelMapper.addAll(commonGbChannelList) > 0;
        } else {
            for (int i = 0; i < commonGbChannelList.size(); i += limit) {
                int toIndex = i + limit;
                if (i + limit > commonGbChannelList.size()) {
                    toIndex = commonGbChannelList.size();
                }
                List<CommonGbChannel> commonGbChannelListSub = commonGbChannelList.subList(i, toIndex);
                int currentResult = commonGbChannelMapper.addAll(commonGbChannelListSub);
                if (currentResult <= 0) {
                    dataSourceTransactionManager.rollback(transactionStatus);
                    logger.info("[同步通用通道]来自国标设备，失败， 写入数据库失败, 国标编号: {}", gbDeviceId);
                    return false;
                }
            }
            result = true;
        }
        deviceChannelMapper.updateCommonChannelId(gbDeviceId);
        // 写入分组数据
        List<Group> allGroup = new ArrayList<>(businessGroupMap.values());
        allGroup.addAll(virtuallyGroupMap.values());
        if (!allGroup.isEmpty()) {
            // 这里也采取只插入新数据的方式
            List<Group> groupInDBList = groupMapper.queryInList(allGroup);
            if (!groupInDBList.isEmpty()) {
                groupInDBList.stream().forEach(groupInDB -> {
                    for (int i = 0; i < allGroup.size(); i++) {
                        if (groupInDB.getCommonGroupDeviceId().equalsIgnoreCase(allGroup.get(i).getCommonGroupDeviceId())) {
                            allGroup.remove(i);
                            break;
                        }
                    }
                });
            }
            if (!allGroup.isEmpty()) {
                if (allGroup.size() <= limit) {
                    if (groupMapper.addAll(allGroup) <= 0) {
                        dataSourceTransactionManager.rollback(transactionStatus);
                        logger.info("[同步通用通道]来自国标设备，失败，添加分组信息失败, 国标编号: {}", gbDeviceId);
                        return false;
                    }
                } else {
                    for (int i = 0; i < allGroup.size(); i += limit) {
                        int toIndex = i + limit;
                        if (i + limit > allGroup.size()) {
                            toIndex = allGroup.size();
                        }
                        List<Group> allGroupSub = allGroup.subList(i, toIndex);
                        if (groupMapper.addAll(allGroupSub) <= 0) {
                            dataSourceTransactionManager.rollback(transactionStatus);
                            logger.info("[同步通用通道]来自国标设备，失败，添加分组信息失败, 国标编号: {}", gbDeviceId);
                            return false;
                        }
                    }
                }
            }
        }
        // 写入地区
        List<Region> allRegion = new ArrayList<>(regionMap.values());

        if (!allRegion.isEmpty()) {
            // 这里也采取只插入新数据的方式
            List<Region> regionInDBList = regionMapper.queryInList(allRegion);
            List<Region> regionInForUpdate = new ArrayList<>();
            if (!regionInDBList.isEmpty()) {
                regionInDBList.stream().forEach(regionInDB -> {
                    for (int i = 0; i < allRegion.size(); i++) {
                        if (regionInDB.getCommonRegionDeviceId().equalsIgnoreCase(allRegion.get(i).getCommonRegionDeviceId())) {
                            if (!regionInDB.getCommonRegionName().equals(allRegion.get(i).getCommonRegionName())) {
                                regionInForUpdate.add(allRegion.get(i));
                            }
                            allRegion.remove(i);
                            break;
                        }
                    }
                });
            }
            if (!allRegion.isEmpty()) {
                if (allRegion.size() <= limit) {
                    if (regionMapper.addAll(allRegion) <= 0) {
                        dataSourceTransactionManager.rollback(transactionStatus);
                        logger.info("[同步通用通道]来自国标设备，失败，添加行政区划信息失败, 国标编号: {}", gbDeviceId);
                        return false;
                    }
                } else {
                    for (int i = 0; i < allRegion.size(); i += limit) {
                        int toIndex = i + limit;
                        if (i + limit > allRegion.size()) {
                            toIndex = allRegion.size();
                        }
                        List<Region> allRegionSub = allRegion.subList(i, toIndex);
                        if (regionMapper.addAll(allRegionSub) <= 0) {
                            dataSourceTransactionManager.rollback(transactionStatus);
                            logger.info("[同步通用通道]来自国标设备，失败，添加行政区划信息失败, 国标编号: {}", gbDeviceId);
                            return false;
                        }
                    }
                }
            }
            // 对于名称变化的地区进行修改
            if (!regionInForUpdate.isEmpty()) {
                regionMapper.updateAllForName(regionInForUpdate);
            }


        }
        dataSourceTransactionManager.commit(transactionStatus);
        return result;
    }

    private String getTopGroupId(Map<String, Group> businessGroupMap, Map<String, Group> virtuallyGroupMap, String commonGroupId, int depth) {
        if (depth >= 16) {
            return null;
        }
        Group group = virtuallyGroupMap.get(commonGroupId);
        if (group == null) {
            return null;
        }
        Gb28181CodeType channelIdType = SipUtils.getChannelIdType(group.getCommonGroupParentId());
        if (channelIdType == Gb28181CodeType.BUSINESS_GROUP) {
            if (businessGroupMap.containsKey(group.getCommonGroupParentId())) {
                return group.getCommonGroupParentId();
            }else {
                return null;
            }
        }
        depth ++;
        return getTopGroupId(businessGroupMap, virtuallyGroupMap, group.getCommonGroupParentId(), depth);
    }

    @Override
    public CommonGbChannel getCommonChannelFromDeviceChannel(DeviceChannel deviceChannel, List<String> syncKeys) {
        if (deviceChannel == null) {
            return null;
        }
        CommonGbChannel commonGbChannel = new CommonGbChannel();
        commonGbChannel.setCommonGbDeviceID(deviceChannel.getChannelId());
        commonGbChannel.setCommonGbStatus(deviceChannel.isStatus());
        commonGbChannel.setType(CommonGbChannelType.GB28181);
        commonGbChannel.setCreateTime(DateUtil.getNow());
        commonGbChannel.setUpdateTime(DateUtil.getNow());
        if (syncKeys == null || syncKeys.isEmpty()) {
            commonGbChannel.setCommonGbName(deviceChannel.getName());
            commonGbChannel.setCommonGbManufacturer(deviceChannel.getManufacture());
            commonGbChannel.setCommonGbModel(deviceChannel.getModel());
            commonGbChannel.setCommonGbOwner(deviceChannel.getOwner());
            if (deviceChannel.getCivilCode() != null) {
                Gb28181CodeType channelIdType = SipUtils.getChannelIdType(deviceChannel.getCivilCode());
                if (channelIdType == Gb28181CodeType.CIVIL_CODE_PROVINCE
                        || channelIdType == Gb28181CodeType.CIVIL_CODE_CITY
                        || channelIdType == Gb28181CodeType.CIVIL_CODE_COUNTY
                        || channelIdType == Gb28181CodeType.CIVIL_CODE_GRASS_ROOTS
                ){
                    commonGbChannel.setCommonGbCivilCode(deviceChannel.getCivilCode());
                }else {
                    logger.warn("[不规范的CivilCode]，deviceId: {}, channel: {}, civilCode: {}",
                            deviceChannel.getDeviceId(),
                            deviceChannel.getChannelId(),
                            deviceChannel.getCivilCode());
                }
            }

            commonGbChannel.setCommonGbCivilCode(deviceChannel.getCivilCode());
            commonGbChannel.setCommonGbBlock(deviceChannel.getBlock());
            commonGbChannel.setCommonGbAddress(deviceChannel.getAddress());
            commonGbChannel.setCommonGbParental(0);
            // 不符合国标的parentId，可以在未分组中找到并重新设置分组信息
            Gb28181CodeType parentIdIdType = SipUtils.getChannelIdType(deviceChannel.getParentId());
            if (parentIdIdType == Gb28181CodeType.VIRTUAL_ORGANIZATION) {
                commonGbChannel.setCommonGbParentID(deviceChannel.getParentId());
            }

            commonGbChannel.setCommonGbSafetyWay(deviceChannel.getSafetyWay());
            commonGbChannel.setCommonGbRegisterWay(deviceChannel.getRegisterWay());
            commonGbChannel.setCommonGbCertNum(deviceChannel.getCertNum());
            commonGbChannel.setCommonGbCertifiable(deviceChannel.getCertifiable());
            commonGbChannel.setCommonGbErrCode(deviceChannel.getErrCode());
            commonGbChannel.setCommonGbEndTime(deviceChannel.getEndTime());
            if (NumberUtils.isParsable(deviceChannel.getSecrecy())) {
                commonGbChannel.setCommonGbSecrecy(Integer.parseInt(deviceChannel.getSecrecy()));
            }
            commonGbChannel.setCommonGbIPAddress(deviceChannel.getIpAddress());
            commonGbChannel.setCommonGbPort(deviceChannel.getPort());
            commonGbChannel.setCommonGbPassword(deviceChannel.getPassword());
            commonGbChannel.setCommonGbLongitude(deviceChannel.getLongitude());
            commonGbChannel.setCommonGbLatitude(deviceChannel.getLatitude());
            commonGbChannel.setCommonGbPtzType(deviceChannel.getPTZType());
//            commonGbChannel.setCommonGbPositionType(deviceChannel.getCommonGbPositionType());
            commonGbChannel.setCommonGbBusinessGroupID(deviceChannel.getBusinessGroupId());
        } else {
            for (String key : syncKeys) {
                switch (key) {
                    case "commonGbName":
                        commonGbChannel.setCommonGbName(deviceChannel.getName());
                        break;
                    case "commonGbManufacturer":
                        commonGbChannel.setCommonGbManufacturer(deviceChannel.getManufacture());
                        break;
                    case "commonGbModel":
                        commonGbChannel.setCommonGbModel(deviceChannel.getModel());
                        break;
                    case "commonGbOwner":
                        commonGbChannel.setCommonGbOwner(deviceChannel.getOwner());
                        break;
                    case "commonGbCivilCode":
                        if (deviceChannel.getCivilCode() == null) {
                            break;
                        }
                        Gb28181CodeType channelIdType = SipUtils.getChannelIdType(deviceChannel.getCivilCode());
                        if (channelIdType == Gb28181CodeType.CIVIL_CODE_PROVINCE
                                || channelIdType == Gb28181CodeType.CIVIL_CODE_CITY
                                || channelIdType == Gb28181CodeType.CIVIL_CODE_COUNTY
                                || channelIdType == Gb28181CodeType.CIVIL_CODE_GRASS_ROOTS
                        ){
                            commonGbChannel.setCommonGbCivilCode(deviceChannel.getCivilCode());
                        }else {
                            logger.warn("[不规范的CivilCode]，deviceId: {}, channel: {}, civilCode: {}",
                                    deviceChannel.getDeviceId(),
                                    deviceChannel.getChannelId(),
                                    deviceChannel.getCivilCode());
                        }
                        commonGbChannel.setCommonGbCivilCode(deviceChannel.getCivilCode());
                        break;
                    case "commonGbBlock":
                        commonGbChannel.setCommonGbBlock(deviceChannel.getBlock());
                        break;
                    case "commonGbAddress":
                        commonGbChannel.setCommonGbAddress(deviceChannel.getAddress());
                        break;
                    case "commonGbParental":
                        commonGbChannel.setCommonGbParental(deviceChannel.getParental());
                        break;
                    case "commonGbParentID":
                        commonGbChannel.setCommonGbParentID(deviceChannel.getParentId());
                        break;
                    case "commonGbSafetyWay":
                        commonGbChannel.setCommonGbSafetyWay(deviceChannel.getSafetyWay());
                        break;
                    case "commonGbRegisterWay":
                        commonGbChannel.setCommonGbRegisterWay(deviceChannel.getRegisterWay());
                        break;
                    case "commonGbCertNum":
                        commonGbChannel.setCommonGbCertNum(deviceChannel.getCertNum());
                        break;
                    case "commonGbCertifiable":
                        commonGbChannel.setCommonGbCertifiable(deviceChannel.getCertifiable());
                        break;
                    case "commonGbErrCode":
                        commonGbChannel.setCommonGbErrCode(deviceChannel.getErrCode());
                        break;
                    case "commonGbEndTime":
                        commonGbChannel.setCommonGbEndTime(deviceChannel.getEndTime());
                        break;
                    case "commonGbSecrecy":
                        if (NumberUtils.isParsable(deviceChannel.getSecrecy())) {
                            commonGbChannel.setCommonGbSecrecy(Integer.parseInt(deviceChannel.getSecrecy()));
                        }
                        break;
                    case "commonGbIPAddress":
                        commonGbChannel.setCommonGbIPAddress(deviceChannel.getIpAddress());
                        break;
                    case "commonGbPort":
                        commonGbChannel.setCommonGbPort(deviceChannel.getPort());
                        break;
                    case "commonGbPassword":
                        commonGbChannel.setCommonGbPassword(deviceChannel.getPassword());
                        break;
                    case "commonGbLongitude":
                        commonGbChannel.setCommonGbLongitude(deviceChannel.getLongitude());
                        break;
                    case "commonGbLatitude":
                        commonGbChannel.setCommonGbLatitude(deviceChannel.getLatitude());
                        break;
                    case "commonGbPtzType":
                        commonGbChannel.setCommonGbPtzType(deviceChannel.getPTZType());
                        break;
                    case "commonGbPositionType":
//                        commonGbChannel.setCommonGbPositionType(deviceChannel.getCommonGbPositionType());
                        break;
                    case "commonGbRoomType":
                        break;
                    case "commonGbUseType":
                        break;
                    case "commonGbSupplyLightType":
                        break;
                    case "commonGbDirectionType":
                        break;
                    case "commonGbResolution":
                        break;
                    case "commonGbBusinessGroupID":
                        commonGbChannel.setCommonGbBusinessGroupID(deviceChannel.getBusinessGroupId());
                        break;
                    case "commonGbDownloadSpeed":
                        break;
                    case "commonGbSVCTimeSupportMode":
                        break;

                }
            }
        }

        return commonGbChannel;
    }

    @Override
    public List<CommonGbChannel> getChannelsInRegion(String civilCode) {
        return null;
    }

    @Override
    public List<CommonGbChannel> getChannelsInBusinessGroup(String businessGroupID) {
        return null;
    }

    @Override
    public void updateChannelFromGb28181DeviceInList(Device device, List<DeviceChannel> deviceChannels) {

    }

    @Override
    public void addChannelFromGb28181DeviceInList(Device device, List<DeviceChannel> deviceChannels) {

    }

    @Override
    public void deleteGbChannelsFromList(List<DeviceChannel> channelList) {
        if (channelList.isEmpty()) {
            return;
        }
        List<String> channelIdList = new ArrayList<>(channelList.size());
        for (DeviceChannel deviceChannel : channelList) {
            channelIdList.add(deviceChannel.getChannelId());
        }
        commonGbChannelMapper.deleteByDeviceIDs(channelIdList);

    }

    @Override
    public void channelsOnlineFromList(List<DeviceChannel> channelList) {
        commonGbChannelMapper.channelsOnlineFromList(channelList);
    }

    @Override
    public void channelsOfflineFromList(List<DeviceChannel> channelList) {
        commonGbChannelMapper.channelsOfflineFromList(channelList);
    }
}
