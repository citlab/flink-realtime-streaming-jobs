package de.tuberlin.cit.test.twittersentiment;

import de.tuberlin.cit.test.twittersentiment.profile.TwitterSentimentJobProfile;
import de.tuberlin.cit.test.twittersentiment.record.TweetRecord;
import de.tuberlin.cit.test.twittersentiment.task.CoFilterTask;
import de.tuberlin.cit.test.twittersentiment.task.HotTopicsMergerTask;
import de.tuberlin.cit.test.twittersentiment.task.HotTopicsRecognitionTask;
import de.tuberlin.cit.test.twittersentiment.task.MapToOneTask;
import de.tuberlin.cit.test.twittersentiment.task.SentimentAnalysisTask;
import de.tuberlin.cit.test.twittersentiment.util.LoadPhaseTweetSource;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.windowing.helper.WindowingHelper;
import org.apache.flink.streaming.statistics.ConstraintUtil;
import org.apache.flink.streaming.statistics.JobGraphLatencyConstraint;
import org.apache.flink.streaming.statistics.SamplingStrategy;
import org.apache.flink.types.StringValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Manual window version.
 *
 * @author Bjoern Lohrmann
 * @author Sascha Wolke
 */
public class TwitterSentimentJob {
	private final static Logger log = LoggerFactory.getLogger(TwitterSentimentJob.class);

	public static void main(String[] args) throws Exception {

		if (args.length != 3) {
			System.err.println("Expected more arguments: " + Arrays.toString(args));
			printUsage();
			System.exit(1);
			return;
		}

		String jmHost = args[0];
		int jmPort = -1;
		if (jmHost.contains(":")) {
			jmHost = args[0].split(":")[0];
			jmPort = Integer.parseInt(args[0].split(":")[1]);
		}

		String outputPath = "file://" + args[1];

		TwitterSentimentJobProfile profile = TwitterSentimentJobProfile.PROFILES.get(args[2]);
		if (profile == null) {
			System.err.printf("Unknown profile: %s\n", args[2]);
			printUsage();
			System.exit(1);
			return;
		}

		int topCount = profile.paraProfile.hotTopicsRecognition.topCount;
		int historySize = profile.paraProfile.hotTopicsRecognition.historySize;
		int topTopicsWindow = HotTopicsRecognitionTask.DEFAULT_TIMEOUT;
		int topTopicsTimeout = HotTopicsMergerTask.DEFAULT_TIMEOUT;

		// set up the execution environment
		final StreamExecutionEnvironment env = setupEnv(jmHost, jmPort);
		env.setBufferTimeout(10);
		env.disableOperatorChaining();

		DataStream<TweetRecord> tweetStream = env.addSource(new LoadPhaseTweetSource(profile.name));

		// dumpStatistics(tweetStream, "Input: %d tweets per second.", Time.of(1, TimeUnit.SECONDS)).printToErr();

		DataStream<Tuple3<Long, String, StringValue>> analyzedTweets =
				tweetStream
						.beginLatencyConstraint(210, "toplist")
						.beginLatencyConstraint(30, "sentiment")
				.flatMap(new HotTopicsRecognitionTask(topCount, historySize, topTopicsWindow))
						.setElasticNumberOfSubtasks(
								profile.paraProfile.hotTopicsRecognition.minimumAmountSubtasks,
								profile.paraProfile.hotTopicsRecognition.maximumAmountSubtasks,
								profile.paraProfile.hotTopicsRecognition.initalAmountSubtasks)
						.setParallelism(profile.paraProfile.hotTopicsRecognition.amountSubstasks)
						.setSamplingStrategy(SamplingStrategy.READ_WRITE)
				.flatMap(new HotTopicsMergerTask(topTopicsTimeout))
						.setParallelism(1)
						.setSamplingStrategy(SamplingStrategy.READ_WRITE)
						.finishLatencyConstraint("toplist")
				.broadcast()
				.connect(tweetStream)
				.flatMap(new CoFilterTask())
						.setElasticNumberOfSubtasks(
								profile.paraProfile.filter.minimumAmountSubtasks,
								profile.paraProfile.filter.maximumAmountSubtasks,
								profile.paraProfile.filter.initalAmountSubtasks)
						.setParallelism(profile.paraProfile.filter.amountSubstasks)
				.map(new SentimentAnalysisTask())
						.setElasticNumberOfSubtasks(
								profile.paraProfile.sentiment.minimumAmountSubtasks,
								profile.paraProfile.sentiment.maximumAmountSubtasks,
								profile.paraProfile.sentiment.initalAmountSubtasks)
						.setParallelism(profile.paraProfile.sentiment.amountSubstasks)
						.finishLatencyConstraint("sentiment");
		analyzedTweets.addSink(new SinkFunction<Tuple3<Long, String, StringValue>>() {
			@Override
			public void invoke(Tuple3<Long, String, StringValue> value) throws Exception {

			}
		}).setParallelism(1);

		// dumpStatistics(analyzedTweets, "Output: %d tweets per second.", Time.of(1, TimeUnit.SECONDS)).printToErr();

		Configuration conf = env.getStreamGraph().getJobGraph().getJobConfiguration();
		List<JobGraphLatencyConstraint> constraints = ConstraintUtil.getConstraints(conf);
		System.out.println("Constraints: " + constraints.size());
		System.out.println();
		for (JobGraphLatencyConstraint constraint : constraints) {
			System.out.printf("%s, %dms\n%s\n\n", constraint.getName(), constraint.getLatencyConstraintInMillis(),
					constraint.getSequence().toString());
		}

		env.execute("TwitterSentimentJob");
	}

	private static StreamExecutionEnvironment setupEnv(String jmHost, int jmPort) throws Exception {
		if (jmHost.equalsIgnoreCase("local")) {
			log.info("Running in local mode.");
			return StreamExecutionEnvironment.getExecutionEnvironment();

		} else {
			log.info("Running in remote mode. Creating jar...");

			// Create jar file for job deployment
			Process p = Runtime.getRuntime().exec("mvn clean package");
			if (p.waitFor() != 0) {
				System.out.println("Failed to build twitter-sentiment-git.jar");
				System.exit(1);
			}

			log.info("Sending job to jobmanager at {}:{}", jmHost, jmPort);
			return StreamExecutionEnvironment.createRemoteEnvironment(
					jmHost, jmPort, "target/twitter-sentiment-git.jar");
		}
	}

	private static void printUsage() {
		System.err.println("Parameters: <jobmanager-host>:<port> <output-file> <profile-name>");
		System.err.println("Run local test cluster with ,,local'' as jobmanager host and empty port.");
		System.err.printf("Available profiles: %s\n",
				Arrays.toString(TwitterSentimentJobProfile.PROFILES.keySet().toArray()));
	}

	private static <T> DataStream<String> dumpStatistics(DataStream<T> stream, final String outputText, WindowingHelper windowPolicy) {
		return stream.map(new MapToOneTask<T>())
				.window(windowPolicy).sum(0).flatten()
				.map(new MapFunction<Long, String>() {
					@Override
					public String map(Long value) throws Exception {
						return String.format(outputText, value);
					}
				});
	}
}
