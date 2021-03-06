/*
 * Copyright (c) 1998-2011 Caucho Technology -- all rights reserved
 *
 * This file is part of Resin(R) Open Source
 *
 * Each copy or derived work must preserve the copyright notice and this
 * notice unmodified.
 *
 * Resin Open Source is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * Resin Open Source is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, or any warranty
 * of NON-INFRINGEMENT.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Resin Open Source; if not, write to the
 *
 *   Free Software Foundation, Inc.
 *   59 Temple Place, Suite 330
 *   Boston, MA 02111-1307  USA
 *
 * @author Scott Ferguson
 */

package com.caucho.cloud.network;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.caucho.cloud.topology.CloudCluster;
import com.caucho.cloud.topology.CloudPod;
import com.caucho.cloud.topology.CloudServer;
import com.caucho.cloud.topology.TriadOwner;
import com.caucho.config.ConfigException;
import com.caucho.config.Configurable;
import com.caucho.config.program.ConfigProgram;
import com.caucho.config.program.ContainerProgram;
import com.caucho.config.types.Period;
import com.caucho.management.server.ClusterServerMXBean;
import com.caucho.network.balance.ClientSocketFactory;
import com.caucho.util.Alarm;

/**
 * Defines a member of the cluster, corresponds to <server> in the conf file.
 *
 * A {@link ServerConnector} obtained with {@link #getServerConnector} is used to actually
 * communicate with this ClusterServer when it is active in another instance of
 * Resin .
 */
public final class ClusterServer {
  private static final Logger log
    = Logger.getLogger(ClusterServer.class.getName());

  private static final int DECODE[];

  private final NetworkClusterSystem _clusterService;
  private final CloudServer _cloudServer;

  // unique identifier for the server within the cluster
  private String _serverClusterId;
  // unique identifier for the server within all Resin clusters
  private String _serverDomainId;
  // the bam admin name
  private String _bamAddress;
  
  private boolean _isRemotePod;

  //
  // config parameters
  //

  private int _loadBalanceConnectionMin = 0;
  private long _loadBalanceIdleTime = 60000L;
  private long _loadBalanceRecoverTime = 15000L;
  private long _loadBalanceSocketTimeout = 600000L;
  private long _loadBalanceWarmupTime = 60000L;

  private long _loadBalanceConnectTimeout = 5000L;

  private int _loadBalanceWeight = 100;
  private boolean _isBackup;
  
  private long _clusterIdleTime = 3 * 60000L;
  
  private ConfigProgram _portDefaults = new ContainerProgram();

  private ContainerProgram _serverProgram
    = new ContainerProgram();

  private String _stage;
  private ArrayList<String> _pingUrls = new ArrayList<String>();

  // runtime

  private ClientSocketFactory _clusterSocketPool;
  private ClientSocketFactory _loadBalanceSocketPool;

  private AtomicBoolean _isHeartbeatActive = new AtomicBoolean();
  private AtomicLong _stateTimestamp = new AtomicLong();
  private AtomicLong _lastHeartbeatTime = new AtomicLong();

  // admin

  private ClusterServerAdmin _admin = new ClusterServerAdmin(this);

  ClusterServer(NetworkClusterSystem networkService,
                CloudServer cloudServer)
  {
    _clusterService = networkService;
    
    if (networkService == null)
      throw new NullPointerException();
    
    _cloudServer = cloudServer;
    cloudServer.getIndex();

    
    if (_clusterService == null)
      throw new NullPointerException();
    
    // XXX: active isn't quite right here
    if (cloudServer.getPod() != networkService.getSelfServer().getPod()) {
      _isRemotePod = true;
      // _isHeartbeatActive.set(true);
    }
    
    _stateTimestamp.set(Alarm.getCurrentTime());

    StringBuilder sb = new StringBuilder();

    sb.append(convert(getIndex()));
    sb.append(convert(getCloudPod().getIndex()));
    sb.append(convert(getCloudPod().getIndex() / 64));

    _serverClusterId = sb.toString();

    String clusterId = cloudServer.getCluster().getId();
    if (clusterId.equals(""))
      clusterId = "default";

    _serverDomainId = _serverClusterId + "." + clusterId.replace('.', '_');

    _bamAddress = _serverDomainId + ".admin.resin";
  }

  /**
   * Gets the server identifier.
   */
  public String getId()
  {
    return _cloudServer.getId();
  }

  public String getDebugId()
  {
    if ("".equals(getId()))
      return "default";
    else
      return getId();
  }

  public CloudServer getCloudServer()
  {
    return _cloudServer;
  }
  
  /**
   * Returns the server's id within the cluster
   */
  public String getServerClusterId()
  {
    return _serverClusterId;
  }

  /**
   * Returns the server's id within all Resin clusters
   */
  public String getServerDomainId()
  {
    return _serverDomainId;
  }

  /**
   * Returns the bam name
   */
  public String getBamAdminName()
  {
    return _bamAddress;
  }

  /**
   * Returns the cluster.
   */
  public CloudCluster getCluster()
  {
    return _cloudServer.getCluster();
  }

  /**
   * Returns the owning pod
   */
  public CloudPod getCloudPod()
  {
    return _cloudServer.getPod();
  }

  /**
   * Returns true if this server is a triad.
   */
  public boolean isTriad()
  {
    return _cloudServer.isTriad();
  }

  /**
   * Returns the pod owner
   */
  public TriadOwner getTriadOwner()
  {
    return TriadOwner.getOwner(getIndex());
  }

  /**
   * Returns the server index within the pod.
   */
  public int getIndex()
  {
    return _cloudServer.getIndex();
  }

 
  /**
   * Gets the address
   */
  public String getAddress()
  {
    return _cloudServer.getAddress();
  }
  
  public boolean isSSL()
  {
    return _cloudServer.isSSL();
  }

  /**
   * Sets true for backups
   */
  public void setBackup(boolean isBackup)
  {
    _isBackup = isBackup;

    if (isBackup)
      setLoadBalanceWeight(1);
  }
  
  public boolean isBackup()
  {
    return _isBackup;
  }

  /**
   * True for a dynamic server
   */
  public boolean isDynamic()
  {
    return ! _cloudServer.isStatic();
  }
  
  //
  // load balance configuration
  //

  /**
   * Sets the loadBalance connection time.
   */
  public void setLoadBalanceConnectTimeout(Period period)
  {
    _loadBalanceConnectTimeout = period.getPeriod();
  }

  /**
   * Gets the loadBalance connection time.
   */
  public long getLoadBalanceConnectTimeout()
  {
    return _loadBalanceConnectTimeout;
  }

  /**
   * The minimum number of load balance connections for green load balancing.
   */
  @Configurable
  public void setLoadBalanceConnectionMin(int min)
  {
    _loadBalanceConnectionMin = min;
  }

  /**
   * The minimum number of load balance connections for green load balancing.
   */
  public int getLoadBalanceConnectionMin()
  {
    return _loadBalanceConnectionMin;
  }

  /**
   * Sets the loadBalance socket time.
   */
  public void setLoadBalanceSocketTimeout(Period period)
  {
    _loadBalanceSocketTimeout = period.getPeriod();
  }

  /**
   * Gets the loadBalance socket time.
   */
  public long getLoadBalanceSocketTimeout()
  {
    return _loadBalanceSocketTimeout;
  }

  /**
   * Sets the loadBalance max-idle-time.
   */
  public void setLoadBalanceIdleTime(Period period)
  {
    _loadBalanceIdleTime = period.getPeriod();
  }

  /**
   * Sets the loadBalance idle-time.
   */
  public long getLoadBalanceIdleTime()
  {
    return _loadBalanceIdleTime;
  }

  /**
   * Sets the loadBalance fail-recover-time.
   */
  public void setLoadBalanceRecoverTime(Period period)
  {
    _loadBalanceRecoverTime = period.getPeriod();
  }

  /**
   * Gets the loadBalance fail-recover-time.
   */
  public long getLoadBalanceRecoverTime()
  {
    return _loadBalanceRecoverTime;
  }

  /**
   * The cluster idle-time.
   */
  public long getClusterIdleTime()
  {
    return _clusterIdleTime;
  }

  
  //
  // port defaults
  //
  

  //
  // Configuration from <server>
  //

  /**
   * Sets the socket's listen property
   */
  public void setAcceptListenBacklog(ConfigProgram program)
  {
    _portDefaults.addProgram(program);
  }

  /**
   * Sets the minimum spare listen.
   */
  public void setAcceptThreadMin(ConfigProgram program)
    throws ConfigException
  {
    _portDefaults.addProgram(program);
  }

  /**
   * Sets the maximum spare listen.
   */
  public void setAcceptThreadMax(ConfigProgram program)
    throws ConfigException
  {
    _portDefaults.addProgram(program);
  }

  /**
   * Sets the maximum connections per port
   */
  public void setConnectionMax(ConfigProgram program)
  {
    _portDefaults.addProgram(program);
  }

  /**
   * Sets the maximum keepalive
   */
  public void setKeepaliveMax(ConfigProgram program)
  {
    _portDefaults.addProgram(program);
  }

  /**
   * Sets the keepalive timeout
   */
  public void setKeepaliveTimeout(ConfigProgram program)
  {
    _portDefaults.addProgram(program);
  }

  /**
   * Sets the keepalive connection timeout
   */
  public void setKeepaliveConnectionTimeMax(ConfigProgram program)
  {
    _portDefaults.addProgram(program);
  }

  /**
   * Sets the select-based keepalive timeout
   */
  public void setKeepaliveSelectEnable(ConfigProgram program)
  {
    _portDefaults.addProgram(program);
  }

  /**
   * Sets the select-based keepalive timeout
   */
  public void setKeepaliveSelectMax(ConfigProgram program)
  {
    _portDefaults.addProgram(program);
  }

  /**
   * Sets the select-based keepalive timeout
   */
  public void setKeepaliveSelectThreadTimeout(ConfigProgram program)
  {
    _portDefaults.addProgram(program);
  }

  /**
   * Sets the suspend timeout
   */
  public void setSocketTimeout(ConfigProgram program)
  {
    _portDefaults.addProgram(program);
  }

  /**
   * Sets the suspend timeout
   */
  public void setSuspendTimeMax(ConfigProgram program)
  {
    _portDefaults.addProgram(program);
  }

  public void setStage(String stage)
  {
    _stage = stage;
  }
  
  public String getStage()
  {
    return _stage;
  }
  
  /**
   * Adds a ping url for availability testing
   */
  public void addPingUrl(String url)
  {
    _pingUrls.add(url);
  }

  /**
   * Returns the ping url list
   */
  public ArrayList<String> getPingUrlList()
  {
    return _pingUrls;
  }

  /**
   * Sets the loadBalance warmup time
   */
  public void setLoadBalanceWarmupTime(Period period)
  {
    _loadBalanceWarmupTime = period.getPeriod();
  }

  /**
   * Gets the loadBalance warmup time
   */
  public long getLoadBalanceWarmupTime()
  {
    return _loadBalanceWarmupTime;
  }

  /**
   * Sets the loadBalance weight
   */
  public void setLoadBalanceWeight(int weight)
  {
    _loadBalanceWeight = weight;
  }

  /**
   * Gets the loadBalance weight
   */
  public int getLoadBalanceWeight()
  {
    return _loadBalanceWeight;
  }

  /**
   * Gets the port.
   */
  public int getPort()
  {
    return getCloudServer().getPort();
  }

  /**
   * Returns true for the self server
   */
  public boolean isSelf()
  {
    return getCloudServer().isSelf();
  }
  
  public boolean isRemote()
  {
    return ! isSelf();
  }

  /**
   * Returns the socket pool as a pod cluster connector.
   */
  public final ClientSocketFactory getClusterSocketPool()
  {
    return _clusterSocketPool;
  }

  /**
   * Returns the socket pool as a load-balancer.
   */
  public final ClientSocketFactory getLoadBalanceSocketPool()
  {
    ClientSocketFactory factory = _loadBalanceSocketPool;
    
    if (factory == null)
      return null;
    
    if (_cloudServer.getState().isDisableSoft()) {
      factory.enableSessionOnly();
    }
    else if (_cloudServer.getState().isDisabled()){
      // server/269g
      factory.disable();
    }
    else {
      factory.enable();
    }
    
    return factory;
  }
  
  /**
   * Returns true if the server is remote and active.
   */
  public final boolean isActiveRemote()
  {
    ClientSocketFactory pool = _clusterSocketPool;

    return pool != null && pool.isActive();
  }
  
  public void addPortDefault(ContainerProgram program)
  {
    _portDefaults.addProgram(program);
  }

  /**
   * Adds a program.
   */
  public void addContentProgram(ConfigProgram program)
  {
    _serverProgram.addProgram(program);
  }

  /**
   * Returns the configuration program for the Server.
   */
  public ConfigProgram getServerProgram()
  {
    return _serverProgram;
  }
  
  /**
   * Returns the port defaults for the Server
   */
  public ConfigProgram getPortDefaults()
  {
    return _portDefaults;
  }

  /**
   * Initialize
   */
  public void init()
  {
    /*
    if (! _isClusterPortConfig)
      applyPortDefaults(_clusterPort);

    _clusterPort.init();
    */

    if (! isSelf() && getCloudServer().getPort() >= 0) {
      _clusterSocketPool = createClusterPool(_clusterService.getServerId());
      _clusterSocketPool.init();
      
      _loadBalanceSocketPool = createLoadBalancePool(_clusterService.getServerId());
      _loadBalanceSocketPool.init();
    }

    _admin.register();
  }

  private ClientSocketFactory createLoadBalancePool(String serverId)
  {
    ClientSocketFactory pool = new ClientSocketFactory(serverId,
                                                       getId(),
                                                       "Resin|LoadBalanceSocket",
                                                       getStatId(),
                                                       getAddress(),
                                                       getPort(),
                                                       isSSL());

    pool.setLoadBalanceConnectTimeout(getLoadBalanceConnectTimeout());
    pool.setLoadBalanceConnectionMin(getLoadBalanceConnectionMin());
    pool.setLoadBalanceSocketTimeout(getLoadBalanceSocketTimeout());
    pool.setLoadBalanceIdleTime(getLoadBalanceIdleTime());
    pool.setLoadBalanceRecoverTime(getLoadBalanceRecoverTime());
    pool.setLoadBalanceWarmupTime(getLoadBalanceWarmupTime());
    pool.setLoadBalanceWeight(getLoadBalanceWeight());
    
    return pool;
  }

  private ClientSocketFactory createClusterPool(String serverId)
  {
    ClientSocketFactory pool = new ClientSocketFactory(serverId,
                                                       getId(),
                                                       "Resin|ClusterSocket",
                                                       getStatId(),
                                                       getAddress(),
                                                       getPort(),
                                                       isSSL());

    pool.setLoadBalanceSocketTimeout(getClusterIdleTime());
    pool.setLoadBalanceIdleTime(getClusterIdleTime());
    
    if (getCloudServer().getPod() == _clusterService.getSelfServer().getPod())
      pool.setHeartbeatServer(true);
    
    return pool;
  }

  private String getStatId()
  {
    String targetCluster = getCluster().getId();

    if ("".equals(targetCluster))
      targetCluster = "default";

    int index = getIndex();

    return String.format("%02x:%s", index, targetCluster);
  }

  public boolean isRemotePod()
  {
    return _isRemotePod;
  }
  
  /**
   * Test if the server is active, i.e. has received an active message.
   */
  public boolean isHeartbeatActive()
  {
    return _isHeartbeatActive.get();
  }

  /**
   * Returns the last state change timestamp.
   */
  public long getStateTimestamp()
  {
    return _stateTimestamp.get();
  }
  
  public long getLastHeartbeatTime()
  {
    return _lastHeartbeatTime.get();
  }

  /**
   * Notify that a start event has been received.
   */
  public boolean notifyHeartbeatStart()
  {
    long now = Alarm.getCurrentTime();
    
    _lastHeartbeatTime.set(now);

    boolean isActive = _isHeartbeatActive.getAndSet(true);
    
    if (isActive)
      return false;
    
    _stateTimestamp.set(now);
    
    _cloudServer.onHeartbeatStart();

    if (_clusterSocketPool != null)
      _clusterSocketPool.notifyHeartbeatStart();

    if (log.isLoggable(Level.FINER))
      log.finer(this + " notify-heartbeat-start");

    _clusterService.notifyHeartbeatStart(this);

    return true;
  }

  /**
   * Notify that a stop event has been received.
   */
  public boolean notifyHeartbeatStop()
  {
    _lastHeartbeatTime.set(0);
    
    boolean isActive = _isHeartbeatActive.getAndSet(false);
    
    if (! isActive)
      return false;
    
    _stateTimestamp.set(Alarm.getCurrentTime());

    log.warning(this + " notify-heartbeat-stop");
    
    _cloudServer.onHeartbeatStop();

    if (_clusterSocketPool != null)
      _clusterSocketPool.notifyHeartbeatStop();

    _clusterService.notifyHeartbeatStop(this);

    return true;
  }

  /**
   * Starts the server.
   */
  public void stopServer()
  {
    _isHeartbeatActive.set(false);
    _stateTimestamp.set(Alarm.getCurrentTime());

    if (_clusterSocketPool != null)
      _clusterSocketPool.notifyHeartbeatStop();
  }

  /**
   * Adds the primary/backup/third digits to the id.
   */
  public void generateIdPrefix(StringBuilder cb)
  {
    cb.append(getServerClusterId());
  }

  //
  // admin
  //

  /**
   * Returns the admin object
   */
  public ClusterServerMXBean getAdmin()
  {
    return _admin;
  }

  /**
   * Close any ports.
   */
  public void close()
  {
    if (_loadBalanceSocketPool != null)
      _loadBalanceSocketPool.close();
    
    if (_clusterSocketPool != null)
      _clusterSocketPool.close();
  }

  @Override
  public String toString()
  {
    return getClass().getSimpleName() + "[id=" + getId() + "]";
  }

  private static char convert(long code)
  {
    code = code & 0x3f;

    if (code < 26)
      return (char) ('a' + code);
    else if (code < 52)
      return (char) ('A' + code - 26);
    else if (code < 62)
      return (char) ('0' + code - 52);
    else if (code == 62)
      return '_';
    else
      return '-';
  }

  public static int decode(int code)
  {
    return DECODE[code & 0x7f];
  }

  static {
    DECODE = new int[128];
    for (int i = 0; i < 64; i++)
      DECODE[(int) convert(i)] = i;
  }
}
