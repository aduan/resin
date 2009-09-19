/*
 * Copyright (c) 1998-2008 Caucho Technology -- all rights reserved
 *
 * This file is part of Resin(R) Open Source
 *
 * Each copy or derived work must preserve the copyright notice and this
 * notice unmodified.
 *
 * Resin Open Source is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2
 * as published by the Free Software Foundation.
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

package com.caucho.admin;

import java.util.concurrent.atomic.AtomicLong;

public final class AverageProbe extends Probe implements AverageSample {
  private final double _scale;

  private final Object _lock = new Object();

  // sample data
  private long _count;
  private double _sum;
  private double _sumSquare;
  private double _max;
  
  private long _lastCount;

  private long _lastAvgCount;
  private double _lastAvgSum;

  // for 95%
  private long _lastStdCount;
  private double _lastStdSum;
  
  public AverageProbe(String name)
  {
    super(name);

    _scale = 1.0;
  }

  public Probe createCount(String name)
  {
    return new CountProbe(name);
  }

  public Probe createMax(String name)
  {
    return new MaxProbe(name);
  }

  public Probe createSigma(String name, int n)
  {
    return new SigmaProbe(name, n);
  }

  public final void add(double value)
  {
    synchronized (_lock) {
      _count++;
      _sum += value;
      _sumSquare += value * value;

      if (_max < value)
        _max = value;
    }
  }
  
  /**
   * Return the probe's next average.
   */
  public final double sample()
  {
    synchronized (_lock) {
      long count = _count;
      long lastCount = _lastAvgCount;
      _lastAvgCount = count;
    
      double sum = _sum;
      double lastSum = _lastAvgSum;
      _lastAvgSum = sum;

      if (count == lastCount)
        return 0;
      else
        return _scale * (sum - lastSum) / (double) (count - lastCount);
    }
  }
  
  /**
   * Return the probe's next sample.
   */
  public final double sampleCount()
  {
    synchronized (_lock) {
      long count = _count;
      long lastCount = _lastCount;
      _lastCount = count;

      return count - lastCount;
    }
  }
  
  /**
   * Return the probe's next 2-sigma
   */
  public final double sampleSigma(int n)
  {
    synchronized (_lock) {
      long count = _count;
      long lastCount = _lastStdCount;
      _lastStdCount = lastCount;
    
      double sum = _sum;
      double lastSum = _lastStdSum;
      _lastStdSum = sum;

      double sumSquare = _sumSquare;
      _sumSquare = 0;

      if (count == lastCount)
        return 0;

      double avg = (sum - lastSum) / (count - lastCount);
      double part = (count - lastCount) * sumSquare - sum * sum;
    
      if (part < 0)
        part = 0;
    
      double std = Math.sqrt(part) / (count - lastCount);

      return _scale * (avg + n * std);
    }
  }
  
  /**
   * Return the probe's next sample.
   */
  public final double sampleMax()
  {
    synchronized (_lock) {
      double max = _max;
      _max = 0;

      return _scale * max;
    }
  }

  class CountProbe extends Probe {
    CountProbe(String name)
    {
      super(name);
    }

    public double sample()
    {
      return sampleCount();
    }
  }

  class MaxProbe extends Probe {
    MaxProbe(String name)
    {
      super(name);
    }

    public double sample()
    {
      return sampleMax();
    }
  }

  class SigmaProbe extends Probe {
    private final int _n;
    
    SigmaProbe(String name, int n)
    {
      super(name);

      _n = n;
    }

    public double sample()
    {
      return sampleSigma(_n);
    }
  }
}