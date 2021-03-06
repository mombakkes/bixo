/*
 * Copyright 2009-2012 Scale Unlimited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.finderbots.miner;

import bixo.config.FetcherPolicy;
import bixo.config.FetcherPolicy.FetcherMode;
import bixo.config.UserAgent;
import bixo.urls.BaseUrlFilter;
import bixo.utils.CrawlDirUtils;
import cascading.flow.Flow;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;
import org.apache.log4j.Logger;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.html.HtmlParser;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings("deprecation")
public class MinerTool {

    private static final Logger LOGGER = Logger.getLogger(MinerTool.class);

    private static void printUsageAndExit(CmdLineParser parser) {
        parser.printUsage(System.err);
        System.exit(-1);
    }

    static void setupWorkingDir(FileSystem fs, Path workingDirPath, String seedUrlsfileName) throws Exception {
        
        // Check if we already have a crawldb
        Path crawlDbPath = null;
        Path loopDirPath = CrawlDirUtils.findLatestLoopDir(fs, workingDirPath);
        if (loopDirPath != null) {//todo: depending on -overwritecrawl erase mined data OR everything. For now everything
            // Clear out any previous loop directory, so we're always starting from scratch
            LOGGER.info("deleting existing working dir");
            while (loopDirPath != null) {
                fs.delete(loopDirPath, true);
                loopDirPath = CrawlDirUtils.findLatestLoopDir(fs, workingDirPath);
            }
        } 

        // Create a "0-<timestamp>" loop sub-directory and import the seed urls
        loopDirPath = CrawlDirUtils.makeLoopDir(fs, workingDirPath, 0);
        crawlDbPath = new Path(loopDirPath, CrawlConfig.CRAWLDB_SUBDIR_NAME);
        //MinerWorkflow.importSeedUrls(crawlDbPath, seedUrlsfileName);
        importUrls(seedUrlsfileName, crawlDbPath);


    }

    private static void importUrls(String urlsFile, Path crawlDbPath) throws Exception {
        Path urlsPath = new Path(urlsFile);
        UrlImporter urlImporter = new UrlImporter(urlsPath, crawlDbPath);
        urlImporter.importUrls(false);
    }

    private static void error(String message, CmdLineParser parser) {
        System.err.println(message);
        printUsageAndExit(parser);
    }

    public static void main(String[] args) throws IOException {

        MinerOptions options = new MinerOptions();
        CmdLineParser parser = new CmdLineParser(options);

        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            printUsageAndExit(parser);
        }

        // Build and run the flow.

        try {

            Path workingDirPath = new Path(options.getWorkingDir());

            JobConf conf = new JobConf();
            FileSystem fs = workingDirPath.getFileSystem(conf);
            setupWorkingDir(fs, workingDirPath, options.getUrlsFile());

            Path latestDirPath = CrawlDirUtils.findLatestLoopDir(fs, workingDirPath);
            if (latestDirPath == null) {
                error("No previous cycle output dirs exist in " + workingDirPath, parser);
            }

            Path crawlDbPath = new Path(latestDirPath, CrawlConfig.CRAWLDB_SUBDIR_NAME);

            UserAgent userAgent = new UserAgent(options.getAgentName(), CrawlConfig.EMAIL_ADDRESS, CrawlConfig.WEB_ADDRESS);

            FetcherPolicy fetcherPolicy = new FetcherPolicy();
            fetcherPolicy.setCrawlDelay(CrawlConfig.DEFAULT_CRAWL_DELAY);
            fetcherPolicy.setMaxContentSize(CrawlConfig.MAX_CONTENT_SIZE);
            fetcherPolicy.setFetcherMode(FetcherMode.EFFICIENT);
            
            // We only care about mime types that the Tika HTML parser can handle,
            // so restrict it to the same.
            Set<String> validMimeTypes = new HashSet<String>();
            Set<MediaType> supportedTypes = new HtmlParser().getSupportedTypes(new ParseContext());
            for (MediaType supportedType : supportedTypes) {
                validMimeTypes.add(String.format("%s/%s", supportedType.getType(), supportedType.getSubtype()));
            }
            fetcherPolicy.setValidMimeTypes(validMimeTypes);

            // By setting up a url filter we only deal with urls that we want to
            // instead of all the urls that we extract.
            String crawlUrlFiltersFile = options.getRegexUrlFiltersFile();
            List<String> crawlUrlPatterns = RegexUrlFilter.getUrlFilterPatterns(crawlUrlFiltersFile);
            BaseUrlFilter crawlUrlFilter = new RegexUrlFilter(crawlUrlPatterns.toArray(new String[crawlUrlPatterns.size()]));

            // setting up a miner filter we will mine only pages that match one of the urls
            String regexUrlFiltersFile = options.getRegexUrlFiltersFile();
            List<String> mineUrlPatterns = RegexUrlFilter.getUrlFilterPatterns(regexUrlFiltersFile);
            BaseUrlFilter mineUrlFilter = new RegexUrlFilter(mineUrlPatterns.toArray(new String[mineUrlPatterns.size()]));

            // Let's limit our crawl to two loops
            for (int curLoop = 1; curLoop <= options.getNumLoops(); curLoop++) {
                Path curLoopDirPath = CrawlDirUtils.makeLoopDir(fs, workingDirPath, curLoop);
                Flow flow = MinerWorkflow.createWebMiningWorkflow(crawlDbPath, curLoopDirPath, fetcherPolicy, userAgent, options, crawlUrlFilter, mineUrlFilter);
                flow.complete();

                // Update crawlDbPath to point to the latest crawl db
                crawlDbPath = new Path(curLoopDirPath, CrawlConfig.CRAWLDB_SUBDIR_NAME);
            }
            
        } catch (Exception e) {
            System.err.println("Exception running job: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(-1);
        }
    }

}
