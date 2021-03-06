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
 * @author Alex Rojkov
 */

package com.caucho.boot;

import com.caucho.server.admin.ManagerClient;
import com.caucho.util.L10N;

public class ProfileCommand extends AbstractManagementCommand
{
  private static final L10N L = new L10N(ProfileCommand.class);

  @Override
  public int doCommand(WatchdogArgs args,
                       WatchdogClient client,
                       ManagerClient managerClient)
  {
    long activeTime = 5 * 1000; // 5 seconds
    String activeTimeArg = args.getArg("-active-time");

    if (activeTimeArg != null)
      activeTime = Long.parseLong(activeTimeArg);

    long period = 10;// sampling period
    String periodArg = args.getArg("-sampling-rate");
    if (periodArg != null)
      period = Long.parseLong(periodArg);

    int depth = 16;
    String depthArg = args.getArg("-depth");
    if (depthArg != null)
      depth = Integer.parseInt(depthArg);

    String result = managerClient.profile(activeTime, period, depth);

    System.out.println(result);

    return 0;
  }

  @Override
  public void usage()
  {
    System.err.println(L.l("usage: bin/resin.sh [-conf <file>] [-server <id>] profile [-address <address>] [-port <port>] -user <user> -password <password> [-active-time <time>] [-sampling-rate <rate>] [-depth <depth>]"));
    System.err.println(L.l(""));
    System.err.println(L.l("description:"));
    System.err.println(L.l("   activates resin internal profiler (Pro version only)"));
    System.err.println(L.l(""));
    System.err.println(L.l("options:"));
    System.err.println(L.l("   -conf <file>          : resin configuration file"));
    System.err.println(L.l("   -server <id>          : id of a server"));
    System.err.println(L.l("   -address <address>    : ip or host name of the server"));
    System.err.println(L.l("   -port <port>          : server http port"));
    System.err.println(L.l("   -user <user>          : user name used for authentication to the server"));
    System.err.println(L.l("   -password <password>  : password used for authentication to the server"));
    System.err.println(L.l("   -active-time          : specifies profiling time span in ms (defaults to 5000 - 5 sec.)" ));
    System.err.println(L.l("   -sampling-rate        : specifies sampling rate (defaults to 10ms)"));
    System.err.println(L.l("   -depth                : specifies stack trace depth (use smaller number (8) for smaller impact, larger – for more information). Defauts to 16."));
  }
}
