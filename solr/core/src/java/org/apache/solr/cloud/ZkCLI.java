/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cloud;

import static org.apache.solr.common.params.CommonParams.NAME;
import static org.apache.solr.common.params.CommonParams.VALUE_LONG;

import com.google.common.annotations.VisibleForTesting;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterProperties;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkMaintenanceUtils;
import org.apache.solr.common.util.Compressor;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.ZLibCompressor;
import org.apache.solr.core.ConfigSetService;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.NodeConfig;
import org.apache.solr.core.SolrXmlConfig;
import org.apache.solr.util.CLIO;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.xml.sax.SAXException;

public class ZkCLI implements CLIO {

  private static final String MAKEPATH = "makepath";
  private static final String PUT = "put";
  private static final String PUT_FILE = "putfile";
  private static final String GET = "get";
  private static final String GET_FILE = "getfile";
  private static final String DOWNCONFIG = "downconfig";
  private static final String ZK_CLI_NAME = "ZkCLI";
  private static final String HELP = "help";
  private static final String LINKCONFIG = "linkconfig";
  private static final String CONFDIR = "confdir";
  private static final String CONFNAME = "confname";
  private static final String ZKHOST = "zkhost";
  private static final String RUNZK = "runzk";
  private static final String SOLRHOME = "solrhome";
  private static final String BOOTSTRAP = "bootstrap";
  static final String UPCONFIG = "upconfig";
  static final String EXCLUDE_REGEX_SHORT = "x";
  static final String EXCLUDE_REGEX = "excluderegex";
  static final String EXCLUDE_REGEX_DEFAULT = ConfigSetService.UPLOAD_FILENAME_EXCLUDE_REGEX;
  private static final String COLLECTION = "collection";
  private static final String CLEAR = "clear";
  private static final String LIST = "list";
  private static final String LS = "ls";
  private static final String CMD = "cmd";
  private static final String CLUSTERPROP = "clusterprop";
  private static final String UPDATEACLS = "updateacls";

  @VisibleForTesting
  public static void setStdout(PrintStream stdout) {
    ZkCLI.stdout = stdout;
  }

  private static PrintStream stdout = CLIO.getOutStream();

  /**
   * Allows you to perform a variety of zookeeper related tasks, such as:
   *
   * <p>Bootstrap the current configs for all collections in solr.xml.
   *
   * <p>Upload a named config set from a given directory.
   *
   * <p>Link a named config set explicity to a collection.
   *
   * <p>Clear ZooKeeper info.
   *
   * <p>If you also pass a solrPort, it will be used to start an embedded zk useful for single
   * machine, multi node tests.
   */
  public static void main(String[] args)
      throws InterruptedException, TimeoutException, IOException, ParserConfigurationException,
          SAXException, KeeperException {

    CommandLineParser parser = new PosixParser();
    Options options = new Options();
    options.addOption(
        Option.builder(CMD)
            .hasArg(true)
            .desc(
                "cmd to run: "
                    + BOOTSTRAP
                    + ", "
                    + UPCONFIG
                    + ", "
                    + DOWNCONFIG
                    + ", "
                    + LINKCONFIG
                    + ", "
                    + MAKEPATH
                    + ", "
                    + PUT
                    + ", "
                    + PUT_FILE
                    + ","
                    + GET
                    + ","
                    + GET_FILE
                    + ", "
                    + LIST
                    + ", "
                    + CLEAR
                    + ", "
                    + UPDATEACLS
                    + ", "
                    + LS)
            .build());

    Option zkHostOption = new Option("z", ZKHOST, true, "ZooKeeper host address");
    options.addOption(zkHostOption);
    Option solrHomeOption =
        new Option("s", SOLRHOME, true, "for " + BOOTSTRAP + ", " + RUNZK + ": solrhome location");
    options.addOption(solrHomeOption);

    options.addOption(
        "d", CONFDIR, true, "for " + UPCONFIG + ": a directory of configuration files");
    options.addOption(
        "n", CONFNAME, true, "for " + UPCONFIG + ", " + LINKCONFIG + ": name of the config set");

    options.addOption("c", COLLECTION, true, "for " + LINKCONFIG + ": name of the collection");

    options.addOption(
        EXCLUDE_REGEX_SHORT,
        EXCLUDE_REGEX,
        true,
        "for " + UPCONFIG + ": files matching this regular expression won't be uploaded");

    options.addOption(
        "r",
        RUNZK,
        true,
        "run zk internally by passing the solr run port - only for clusters on one machine (tests, dev)");

    options.addOption("h", HELP, false, "bring up this help page");
    options.addOption(NAME, true, "name of the cluster property to set");
    options.addOption(VALUE_LONG, true, "value of the cluster to set");

    try {
      // parse the command line arguments
      CommandLine line = parser.parse(options, args);

      if (line.hasOption(HELP) || !line.hasOption(ZKHOST) || !line.hasOption(CMD)) {
        // automatically generate the help statement
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp(ZK_CLI_NAME, options);
        stdout.println("Examples:");
        stdout.println(
            "zkcli.sh -zkhost localhost:9983 -cmd " + BOOTSTRAP + " -" + SOLRHOME + " /opt/solr");
        stdout.println(
            "zkcli.sh -zkhost localhost:9983 -cmd "
                + UPCONFIG
                + " -"
                + CONFDIR
                + " /opt/solr/collection1/conf"
                + " -"
                + CONFNAME
                + " myconf");
        stdout.println(
            "zkcli.sh -zkhost localhost:9983 -cmd "
                + DOWNCONFIG
                + " -"
                + CONFDIR
                + " /opt/solr/collection1/conf"
                + " -"
                + CONFNAME
                + " myconf");
        stdout.println(
            "zkcli.sh -zkhost localhost:9983 -cmd "
                + LINKCONFIG
                + " -"
                + COLLECTION
                + " collection1"
                + " -"
                + CONFNAME
                + " myconf");
        stdout.println("zkcli.sh -zkhost localhost:9983 -cmd " + MAKEPATH + " /apache/solr");
        stdout.println("zkcli.sh -zkhost localhost:9983 -cmd " + PUT + " /solr.conf 'conf data'");
        stdout.println(
            "zkcli.sh -zkhost localhost:9983 -cmd "
                + PUT_FILE
                + " /solr.xml /User/myuser/solr/solr.xml");
        stdout.println("zkcli.sh -zkhost localhost:9983 -cmd " + GET + " /solr.xml");
        stdout.println(
            "zkcli.sh -zkhost localhost:9983 -cmd " + GET_FILE + " /solr.xml solr.xml.file");
        stdout.println("zkcli.sh -zkhost localhost:9983 -cmd " + CLEAR + " /solr");
        stdout.println("zkcli.sh -zkhost localhost:9983 -cmd " + LIST);
        stdout.println("zkcli.sh -zkhost localhost:9983 -cmd " + LS + " /solr/live_nodes");
        stdout.println(
            "zkcli.sh -zkhost localhost:9983 -cmd "
                + CLUSTERPROP
                + " -"
                + NAME
                + " urlScheme -"
                + VALUE_LONG
                + " https");
        stdout.println("zkcli.sh -zkhost localhost:9983 -cmd " + UPDATEACLS + " /solr");
        return;
      }

      // start up a tmp zk server first
      String zkServerAddress = line.getOptionValue(ZKHOST);
      String solrHome = line.getOptionValue(SOLRHOME);
      if (StrUtils.isNullOrEmpty(solrHome)) {
        solrHome = System.getProperty("solr.home");
      }

      String solrPort = null;
      if (line.hasOption(RUNZK)) {
        if (!line.hasOption(SOLRHOME)) {
          stdout.println("-" + SOLRHOME + " is required for " + RUNZK);
          System.exit(1);
        }
        solrPort = line.getOptionValue(RUNZK);
      }

      SolrZkServer zkServer = null;
      if (solrPort != null) {
        zkServer =
            new SolrZkServer(
                "true",
                null,
                new File(solrHome, "/zoo_data"),
                solrHome,
                Integer.parseInt(solrPort));
        zkServer.parseConfig();
        zkServer.start();
      }

      int minStateByteLenForCompression = -1;
      Compressor compressor = new ZLibCompressor();

      if (solrHome != null) {
        try {
          Path solrHomePath = Paths.get(solrHome);
          Properties props = new Properties();
          props.put(SolrXmlConfig.ZK_HOST, zkServerAddress);
          NodeConfig nodeConfig = NodeConfig.loadNodeConfig(solrHomePath, props);
          minStateByteLenForCompression =
              nodeConfig.getCloudConfig().getMinStateByteLenForCompression();
          String stateCompressorClass = nodeConfig.getCloudConfig().getStateCompressorClass();
          if (StrUtils.isNotNullOrEmpty(stateCompressorClass)) {
            Class<? extends Compressor> compressionClass =
                Class.forName(stateCompressorClass).asSubclass(Compressor.class);
            compressor = compressionClass.getDeclaredConstructor().newInstance();
          }
        } catch (SolrException e) {
          // Failed to load solr.xml
          stdout.println(
              "Failed to load solr.xml from ZK or SolrHome, put/get operations on compressed data will use data as is. If you intention is to read and de-compress data or compress and write data, then solr.xml must be accessible.");
        } catch (ClassNotFoundException
            | NoSuchMethodException
            | InstantiationException
            | IllegalAccessException
            | InvocationTargetException e) {
          stdout.println("Unable to find or instantiate compression class: " + e.getMessage());
          System.exit(1);
        }
      }

      try (SolrZkClient zkClient =
          new SolrZkClient.Builder()
              .withUrl(zkServerAddress)
              .withTimeout(30000, TimeUnit.MILLISECONDS)
              .withConnTimeOut(30000, TimeUnit.MILLISECONDS)
              .withReconnectListener(() -> {})
              .withCompressor(compressor)
              .build()) {
        if (line.getOptionValue(CMD).equalsIgnoreCase(BOOTSTRAP)) {
          if (!line.hasOption(SOLRHOME)) {
            stdout.println("-" + SOLRHOME + " is required for " + BOOTSTRAP);
            System.exit(1);
          }

          CoreContainer cc = new CoreContainer(Paths.get(solrHome), new Properties());
          cc.setCoreConfigService(new ZkConfigSetService(zkClient));

          if (!ZkController.checkChrootPath(zkServerAddress, true)) {
            stdout.println("A chroot was specified in zkHost but the znode doesn't exist. ");
            System.exit(1);
          }

          ConfigSetService.bootstrapConf(cc);

          // No need to close the CoreContainer, as it wasn't started
          // up in the first place...

        } else if (line.getOptionValue(CMD).equalsIgnoreCase(UPCONFIG)) {
          if (!line.hasOption(CONFDIR) || !line.hasOption(CONFNAME)) {
            stdout.println("-" + CONFDIR + " and -" + CONFNAME + " are required for " + UPCONFIG);
            System.exit(1);
          }
          String confDir = line.getOptionValue(CONFDIR);
          String confName = line.getOptionValue(CONFNAME);
          final String excludeExpr = line.getOptionValue(EXCLUDE_REGEX, EXCLUDE_REGEX_DEFAULT);

          if (!ZkController.checkChrootPath(zkServerAddress, true)) {
            stdout.println("A chroot was specified in zkHost but the znode doesn't exist. ");
            System.exit(1);
          }
          final Pattern excludePattern = Pattern.compile(excludeExpr);
          ZkMaintenanceUtils.uploadToZK(
              zkClient,
              Paths.get(confDir),
              ZkMaintenanceUtils.CONFIGS_ZKNODE + "/" + confName,
              excludePattern);
        } else if (line.getOptionValue(CMD).equalsIgnoreCase(DOWNCONFIG)) {
          if (!line.hasOption(CONFDIR) || !line.hasOption(CONFNAME)) {
            stdout.println("-" + CONFDIR + " and -" + CONFNAME + " are required for " + DOWNCONFIG);
            System.exit(1);
          }
          String confDir = line.getOptionValue(CONFDIR);
          String confName = line.getOptionValue(CONFNAME);
          ZkMaintenanceUtils.downloadFromZK(
              zkClient, ZkMaintenanceUtils.CONFIGS_ZKNODE + "/" + confName, Paths.get(confDir));
        } else if (line.getOptionValue(CMD).equalsIgnoreCase(LINKCONFIG)) {
          if (!line.hasOption(COLLECTION) || !line.hasOption(CONFNAME)) {
            stdout.println(
                "-" + COLLECTION + " and -" + CONFNAME + " are required for " + LINKCONFIG);
            System.exit(1);
          }
          String collection = line.getOptionValue(COLLECTION);
          String confName = line.getOptionValue(CONFNAME);

          ZkController.linkConfSet(zkClient, collection, confName);
        } else if (line.getOptionValue(CMD).equalsIgnoreCase(LIST)) {
          zkClient.printLayoutToStream(stdout);
        } else if (line.getOptionValue(CMD).equals(LS)) {

          List<String> argList = line.getArgList();
          if (argList.size() != 1) {
            stdout.println("-" + LS + " requires one arg - the path to list");
            System.exit(1);
          }

          StringBuilder sb = new StringBuilder();
          String path = argList.get(0);
          zkClient.printLayout(path == null ? "/" : path, 0, sb);
          stdout.println(sb);

        } else if (line.getOptionValue(CMD).equalsIgnoreCase(CLEAR)) {
          List<String> arglist = line.getArgList();
          if (arglist.size() != 1) {
            stdout.println("-" + CLEAR + " requires one arg - the path to clear");
            System.exit(1);
          }
          zkClient.clean(arglist.get(0));
        } else if (line.getOptionValue(CMD).equalsIgnoreCase(MAKEPATH)) {
          List<String> arglist = line.getArgList();
          if (arglist.size() != 1) {
            stdout.println("-" + MAKEPATH + " requires one arg - the path to make");
            System.exit(1);
          }
          zkClient.makePath(arglist.get(0), true);
        } else if (line.getOptionValue(CMD).equalsIgnoreCase(PUT)) {
          List<String> arglist = line.getArgList();
          if (arglist.size() != 2) {
            stdout.println(
                "-" + PUT + " requires two args - the path to create and the data string");
            System.exit(1);
          }
          String path = arglist.get(0);
          byte[] data = arglist.get(1).getBytes(StandardCharsets.UTF_8);
          if (shouldCompressData(data, path, minStateByteLenForCompression)) {
            // state.json should be compressed before being put to ZK
            data = compressor.compressBytes(data, data.length / 10);
          }
          if (zkClient.exists(path, true)) {
            zkClient.setData(path, data, true);
          } else {
            zkClient.create(path, data, CreateMode.PERSISTENT, true);
          }
        } else if (line.getOptionValue(CMD).equalsIgnoreCase(PUT_FILE)) {
          List<String> arglist = line.getArgList();
          if (arglist.size() != 2) {
            stdout.println(
                "-"
                    + PUT_FILE
                    + " requires two args - the path to create in ZK and the path to the local file");
            System.exit(1);
          }

          String path = arglist.get(0);
          byte[] data = Files.readAllBytes(Path.of(arglist.get(1)));
          if (shouldCompressData(data, path, minStateByteLenForCompression)) {
            // state.json should be compressed before being put to ZK
            data = compressor.compressBytes(data, data.length / 10);
          }
          if (zkClient.exists(path, true)) {
            zkClient.setData(path, data, true);
          } else {
            zkClient.create(path, data, CreateMode.PERSISTENT, true);
          }
        } else if (line.getOptionValue(CMD).equalsIgnoreCase(GET)) {
          List<String> arglist = line.getArgList();
          if (arglist.size() != 1) {
            stdout.println("-" + GET + " requires one arg - the path to get");
            System.exit(1);
          }
          byte[] data = zkClient.getData(arglist.get(0), null, null, true);
          stdout.println(new String(data, StandardCharsets.UTF_8));
        } else if (line.getOptionValue(CMD).equalsIgnoreCase(GET_FILE)) {
          List<String> arglist = line.getArgList();
          if (arglist.size() != 2) {
            stdout.println(
                "-" + GET_FILE + "requires two args - the path to get and the file to save it to");
            System.exit(1);
          }
          byte[] data = zkClient.getData(arglist.get(0), null, null, true);
          Files.write(Path.of(arglist.get(1)), data);
        } else if (line.getOptionValue(CMD).equals(UPDATEACLS)) {
          List<String> arglist = line.getArgList();
          if (arglist.size() != 1) {
            stdout.println("-" + UPDATEACLS + " requires one arg - the path to update");
            System.exit(1);
          }
          zkClient.updateACLs(arglist.get(0));
        } else if (line.getOptionValue(CMD).equalsIgnoreCase(CLUSTERPROP)) {
          if (!line.hasOption(NAME)) {
            stdout.println("-" + NAME + " is required for " + CLUSTERPROP);
          }
          String propertyName = line.getOptionValue(NAME);
          // If -val option is missing, we will use the null value. This is required to maintain
          // compatibility with Collections API.
          String propertyValue = line.getOptionValue(VALUE_LONG);
          ClusterProperties props = new ClusterProperties(zkClient);
          try {
            props.setClusterProperty(propertyName, propertyValue);
          } catch (IOException ex) {
            stdout.println(
                "Unable to set the cluster property due to following error : "
                    + ex.getLocalizedMessage());
            System.exit(1);
          }
        } else {
          // If not cmd matches
          stdout.println("Unknown command " + line.getOptionValue(CMD) + ". Use -h to get help.");
          System.exit(1);
        }
      } finally {
        if (solrPort != null) {
          zkServer.stop();
        }
      }
    } catch (ParseException exp) {
      stdout.println("Unexpected exception:" + exp.getMessage());
    }
  }

  private static boolean shouldCompressData(
      byte[] data, String path, int minStateByteLenForCompression) {
    if (path.endsWith("state.json")
        && minStateByteLenForCompression > -1
        && data.length > minStateByteLenForCompression) {
      // state.json should be compressed before being put to ZK
      return true;
    }
    return false;
  }
}
