import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

public class Main extends Configured implements Tool {

    public static PointWritable[] initRandomCentroids(int kClusters, int nLineOfInputFile, String inputFilePath,
            Configuration conf) throws IOException {
        System.out.println("Initializing random " + kClusters + " centroids...");
        PointWritable[] points = new PointWritable[kClusters];

        List<Integer> lstLinePos = new ArrayList<>();
        Random random = new Random();
        int pos;
        while (lstLinePos.size() < kClusters) {
            pos = random.nextInt(nLineOfInputFile);
            if (!lstLinePos.contains(pos)) {
                lstLinePos.add(pos);
            }
        }
        Collections.sort(lstLinePos);

        FileSystem hdfs = FileSystem.get(conf);
        FSDataInputStream in = hdfs.open(new Path(inputFilePath));
        BufferedReader br = new BufferedReader(new InputStreamReader(in));

        int row = 0;
        int i = 0;
        while (i < lstLinePos.size()) {
            pos = lstLinePos.get(i);
            String point = br.readLine();
            if (row == pos) {
                points[i] = new PointWritable(point.split(","));
                i++;
            }
            row++;
        }
        br.close();
        return points;
    }

    public static void saveCentroidsForShared(Configuration conf, PointWritable[] points) {
        for (int i = 0; i < points.length; i++) {
            String centroidName = "C" + i;
            conf.unset(centroidName);
            conf.set(centroidName, points[i].toString());
        }
    }

    public static PointWritable[] readCentroidsFromReducerOutput(Configuration conf, int kClusters,
            String folderOutputPath) throws IOException {
        PointWritable[] points = new PointWritable[kClusters];
        FileSystem hdfs = FileSystem.get(conf);
        FileStatus[] status = hdfs.listStatus(new Path(folderOutputPath));

        for (FileStatus fileStatus : status) {
            if (!fileStatus.getPath().toString().endsWith("_SUCCESS")) {
                Path outFilePath = fileStatus.getPath();
//                System.out.println("read " + outFilePath);
                BufferedReader br = new BufferedReader(new InputStreamReader(hdfs.open(outFilePath)));
                String line;
                while ((line = br.readLine()) != null) {
                    System.out.println(line);

                    String[] strCentroidInfo = line.split("\t"); // Split line in K,V
                    int centroidId = Integer.parseInt(strCentroidInfo[0]);
                    String[] attrPoint = strCentroidInfo[1].split(",");
                    points[centroidId] = new PointWritable(attrPoint);
                }
                br.close();
            }
        }
        hdfs.delete(new Path(folderOutputPath), true);

        return points;
    }

    private static boolean checkStopKMean(PointWritable[] oldCentroids, PointWritable[] newCentroids, float threshold) {
        System.out.println("Check for stop K-Means if distance <= " + threshold);
        for (int i = 0; i < oldCentroids.length; i++) {
            double dist = oldCentroids[i].calcDistance(newCentroids[i]);
            System.out.println("distance centroid[" + i + "] changed: " + dist + " (threshold:" + threshold + ")");
            if (dist > threshold) {
                return false;
            }
        }
        return true;
    }

    private static void writeFinalResultNew(Configuration conf, PointWritable[] centroidsFound, String inputFilePath,
            String outputFolderPath, PointWritable[] centroidsInit) throws IOException {
        FileSystem hdfs = FileSystem.get(conf);
        // Tệp để lưu các tâm cụm
        FSDataOutputStream centroidsOutputStream = hdfs.create(new Path(outputFolderPath + "/centroids.txt"), true);
        BufferedWriter centroidsWriter = new BufferedWriter(new OutputStreamWriter(centroidsOutputStream));

        // Tệp để lưu các điểm dữ liệu và cụm tương ứng
        FSDataOutputStream pointsOutputStream = hdfs.create(new Path(outputFolderPath + "/clusters.txt"), true);
        BufferedWriter pointsWriter = new BufferedWriter(new OutputStreamWriter(pointsOutputStream));

        FSDataInputStream in = hdfs.open(new Path(inputFilePath));
        BufferedReader br = new BufferedReader(new InputStreamReader(in));

        PointWritable pointInput = new PointWritable();
        int[] count = new int[centroidsFound.length];

        String line;
        while ((line = br.readLine()) != null) {
            String[] arrPropPoint = line.split(",");
            pointInput.set(arrPropPoint);

            double minDistance = Double.MAX_VALUE;
            int centroidIdNearest = 0;
            for (int i = 0; i < centroidsFound.length; i++) {
                double distance = pointInput.calcDistance(centroidsFound[i]);
                if (distance < minDistance) {
                    centroidIdNearest = i;
                    minDistance = distance;
                }
            }

            pointsWriter.write("Point: (" + pointInput + ") belongs to Centroid[" + centroidIdNearest + "]");
            pointsWriter.newLine();
            count[centroidIdNearest]++;
        }

        // Ghi thông tin tâm cụm vào tệp centroids.txt
        for (int i = 0; i < centroidsFound.length; i++) {
            centroidsWriter.write("Centroid[" + i + "]: (" + centroidsFound[i] + ") Count: " + count[i]);
            centroidsWriter.newLine();
        }

        centroidsWriter.close();
        pointsWriter.close();
        br.close();
        hdfs.close();
    }

    public static PointWritable[] copyCentroids(PointWritable[] points) {
        PointWritable[] savedPoints = new PointWritable[points.length];
        for (int i = 0; i < savedPoints.length; i++) {
            savedPoints[i] = PointWritable.copy(points[i]);
        }
        return savedPoints;
    }

    public static int MAX_LOOP = 50;

    public static void printCentroids(PointWritable[] points, String name) {
        System.out.println("=> CURRENT CENTROIDS:");
        for (int i = 0; i < points.length; i++) {
            System.out.println("centroids(" + name + ")[" + i + "]=> :" + points[i]);
        }
        System.out.println("----------------------------------");
    }

    public int run(String[] args) throws Exception {
        Configuration conf = getConf();
        String inputFilePath = conf.get("in", null);
        String outputFolderPath = conf.get("out", null);

        int nClusters = conf.getInt("k", 3);
        float thresholdStop = conf.getFloat("thresh", 0.001f);
        int numLineOfInputFile = conf.getInt("lines", 0);
        MAX_LOOP = conf.getInt("maxloop", 50);
        int nReduceTask = conf.getInt("NumReduceTask", 1);

        if (inputFilePath == null || outputFolderPath == null || numLineOfInputFile == 0) {
            System.err.printf(
                    "Usage: %s -Din <input file name> -Dlines <number of lines in input file> -Dout <Folder output>\n",
                    getClass().getSimpleName());
            ToolRunner.printGenericCommandUsage(System.err);
            return -1;
        }

        PointWritable[] oldCentroidPoints = initRandomCentroids(nClusters, numLineOfInputFile, inputFilePath, conf);
        PointWritable[] centroidsInit = copyCentroids(oldCentroidPoints);
        saveCentroidsForShared(conf, oldCentroidPoints);

        int nLoop = 0;
        PointWritable[] newCentroidPoints = null;
        long t1 = new Date().getTime();

        while (nLoop < MAX_LOOP) {
            nLoop++;
            Job job = Job.getInstance(conf, "K-Mean");
            job.setJarByClass(Main.class);
            job.setMapperClass(KMapper.class);
            job.setCombinerClass(KCombiner.class);
            job.setReducerClass(KReducer.class);
            job.setMapOutputKeyClass(LongWritable.class);
            job.setMapOutputValueClass(PointWritable.class);

            job.setOutputKeyClass(Text.class);
            job.setOutputValueClass(Text.class);

            FileInputFormat.addInputPath(job, new Path(inputFilePath));
            FileOutputFormat.setOutputPath(job, new Path(outputFolderPath));
            job.setOutputFormatClass(TextOutputFormat.class);
            job.setNumReduceTasks(nReduceTask);

            if (!job.waitForCompletion(true)) {
                return -1;
            }

            newCentroidPoints = readCentroidsFromReducerOutput(conf, nClusters, outputFolderPath);
            if (checkStopKMean(newCentroidPoints, oldCentroidPoints, thresholdStop)) {
                break;
            }
            oldCentroidPoints = copyCentroids(newCentroidPoints);
            saveCentroidsForShared(conf, newCentroidPoints);
        }

        if (newCentroidPoints != null) {
            writeFinalResultNew(conf, newCentroidPoints, inputFilePath, outputFolderPath, centroidsInit);
        }

        System.out.println("K-MEANS CLUSTERING FINISHED!");
        System.out.println("Loop: " + nLoop);
        System.out.println("Time: " + (new Date().getTime() - t1) + "ms");
        return 1;
    }

    public static void main(String[] args) throws Exception {
        int exitCode = ToolRunner.run(new Main(), args);
        System.exit(exitCode);
    }
}
