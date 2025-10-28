import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import scala.Tuple2;

import java.io.*;
import java.util.*;

public class KMeansMain {

    public static class Point implements Serializable {
        private double[] values;

        public Point(double[] values) {
            this.values = values;
        }

        public Point(String[] strValues) {
            this.values = new double[strValues.length];
            for (int i = 0; i < strValues.length; i++) {
                this.values[i] = Double.parseDouble(strValues[i]);
            }
        }

        public double[] getValues() {
            return values;
        }

        public double calcDistance(Point other) {
            double sum = 0.0;
            for (int i = 0; i < values.length; i++) {
                sum += Math.pow(values[i] - other.values[i], 2);
            }
            return Math.sqrt(sum);
        }

        public Point add(Point other) {
            double[] newValues = new double[values.length];
            for (int i = 0; i < values.length; i++) {
                newValues[i] = this.values[i] + other.values[i];
            }
            return new Point(newValues);
        }

        public Point divide(int count) {
            double[] newValues = new double[values.length];
            for (int i = 0; i < values.length; i++) {
                newValues[i] = this.values[i] / count;
            }
            return new Point(newValues);
        }

        public Point copy() {
            return new Point(Arrays.copyOf(values, values.length));
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(String.format("%.5f", values[i]));
            }
            return sb.toString();
        }
    }

    public static Point[] initRandomCentroids(JavaRDD<String> inputData, int k, long seed) {
        System.out.println("Khoi tao ngau nhien " + k + " centroids...");
        List<String> sampledLines = inputData.takeSample(false, k, seed);
        Point[] centroids = new Point[k];
        for (int i = 0; i < k; i++) {
            centroids[i] = new Point(sampledLines.get(i).split(","));
        }
        return centroids;
    }

    public static int findNearestCentroid(Point point, Point[] centroids) {
        double minDistance = Double.MAX_VALUE;
        int nearestIndex = 0;
        for (int i = 0; i < centroids.length; i++) {
            double distance = point.calcDistance(centroids[i]);
            if (distance < minDistance) {
                minDistance = distance;
                nearestIndex = i;
            }
        }
        return nearestIndex;
    }

    // Kiem tra hoi tu: so sanh NEW vs OLD (giong Hadoop Main.java)
    public static boolean hasConverged(Point[] newCentroids, Point[] oldCentroids, double threshold) {
        System.out.println("Kiem tra hoi tu voi nguong: " + threshold);
        for (int i = 0; i < newCentroids.length; i++) {
            double dist = newCentroids[i].calcDistance(oldCentroids[i]);
            System.out.println("Khoang cach centroid[" + i + "]: " + dist);
            if (dist > threshold) {
                return false;
            }
        }
        return true;
    }

    public static void writeFinalResults(JavaSparkContext sc, Point[] centroids, JavaRDD<String> inputData,
                                         String outputPath) {
        System.out.println("Ghi ket qua cuoi cung...");

        // Broadcast centroids
        Broadcast<Point[]> bcCentroids = sc.broadcast(centroids);

        // Tinh toan cluster assignments
        JavaPairRDD<Integer, Point> assignments = inputData.mapToPair(line -> {
            Point point = new Point(line.split(","));
            int centroidId = findNearestCentroid(point, bcCentroids.value());
            return new Tuple2<>(centroidId, point);
        });

        // Dem so diem moi cluster
        Map<Integer, Long> clusterCounts = assignments.countByKey();

        // Ghi centroids.txt
        List<String> centroidLines = new ArrayList<>();
        for (int i = 0; i < centroids.length; i++) {
            long count = clusterCounts.getOrDefault(i, 0L);
            centroidLines.add("Centroid[" + i + "]: (" + centroids[i] + ") Count: " + count);
        }
        sc.parallelize(centroidLines, 1).saveAsTextFile(outputPath + "/centroids.txt");

        // Ghi clusters.txt
        JavaRDD<String> clusterAssignments = assignments.map(tuple ->
                "Point: (" + tuple._2 + ") belongs to Centroid[" + tuple._1 + "]"
        );
        clusterAssignments.saveAsTextFile(outputPath + "/clusters.txt");

        bcCentroids.destroy();
    }

    public static void main(String[] args) {
        // Cau hinh Spark
        SparkConf conf = new SparkConf().setAppName("K-Means Clustering - Spark");

        // Parse arguments
        String inputPath = null;
        String outputPath = null;
        int k = 4;
        double threshold = 0.001;
        int maxIterations = 50;
        String master = "local[*]";

        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-D")) {
                String arg = args[i].substring(2);
                String[] parts = arg.split("=", 2);
                if (parts.length == 2) {
                    switch (parts[0]) {
                        case "in":
                            inputPath = parts[1];
                            break;
                        case "out":
                            outputPath = parts[1];
                            break;
                        case "k":
                            k = Integer.parseInt(parts[1]);
                            break;
                        case "thresh":
                            threshold = Double.parseDouble(parts[1]);
                            break;
                        case "maxloop":
                            maxIterations = Integer.parseInt(parts[1]);
                            break;
                        case "master":
                            master = parts[1];
                            break;
                    }
                }
            }
        }

        if (inputPath == null || outputPath == null) {
            System.err.println("Usage: -Din=<input> -Dout=<output> [-Dk=4] [-Dthresh=0.001] [-Dmaxloop=50] [-Dmaster=local[*]]");
            System.exit(-1);
        }

        conf.setMaster(master);
        JavaSparkContext sc = new JavaSparkContext(conf);

        try {
            long startTime = System.currentTimeMillis();

            // Doc du lieu dau vao
            System.out.println("Doc du lieu tu: " + inputPath);
            JavaRDD<String> inputData = sc.textFile(inputPath).cache();
            long recordCount = inputData.count();
            System.out.println("So ban ghi: " + recordCount);

            // Khoi tao centroids ngau nhien
            Point[] centroids = initRandomCentroids(inputData, k, System.currentTimeMillis());
            Point[] oldCentroids = null;

            int iteration = 0;
            Point[] newCentroids = null;
            while (iteration < maxIterations) {
                iteration++;
                System.out.println("\n=== Vong lap " + iteration + " ===");

                // Broadcast centroids
                Broadcast<Point[]> bcCentroids = sc.broadcast(centroids);

                // Map: Gan moi diem vao centroid gan nhat
                JavaPairRDD<Integer, Point> pointsWithCentroids = inputData.mapToPair(line -> {
                    Point point = new Point(line.split(","));
                    int centroidId = findNearestCentroid(point, bcCentroids.value());
                    return new Tuple2<>(centroidId, point);
                });

                // Reduce: Tinh centroid moi cho moi cluster
                JavaPairRDD<Integer, Tuple2<Point, Integer>> aggregated = pointsWithCentroids
                        .mapValues(point -> new Tuple2<>(point, 1))
                        .reduceByKey((t1, t2) -> new Tuple2<>(t1._1.add(t2._1), t1._2 + t2._2));

                Map<Integer, Tuple2<Point, Integer>> clusterData = aggregated.collectAsMap();

                // Luu OLD centroids truoc khi cap nhat (giong Hadoop)
                oldCentroids = new Point[centroids.length];
                for (int i = 0; i < centroids.length; i++) {
                    oldCentroids[i] = centroids[i].copy();
                }

                // Cap nhat NEW centroids
                newCentroids = new Point[centroids.length];
                for (int i = 0; i < centroids.length; i++) {
                    if (clusterData.containsKey(i)) {
                        Tuple2<Point, Integer> data = clusterData.get(i);
                        newCentroids[i] = data._1.divide(data._2);
                    } else {
                        newCentroids[i] = centroids[i].copy();
                    }
                }

                bcCentroids.destroy();

                // Kiem tra hoi tu (so sanh NEW vs OLD)
                if (hasConverged(newCentroids, oldCentroids, threshold)) {
                    System.out.println("Hoi tu tai vong lap " + iteration);
                    centroids = newCentroids; // Cap nhat centroids cuoi cung
                    break;
                }
                
                // Chuyen NEW thanh current cho vong lap tiep theo
                centroids = newCentroids;
            }

            long processingTime = System.currentTimeMillis() - startTime;

            // Ghi ket qua
            writeFinalResults(sc, centroids, inputData, outputPath);

            // In thong tin hieu nang
            System.out.println("\n=== THONG TIN HIEU NANG ===");
            System.out.println("So vong lap: " + iteration);
            System.out.println("Thoi gian xu ly: " + processingTime + " ms");
            System.out.println("So ban ghi: " + recordCount);
            System.out.println("Throughput: " + String.format("%.2f", recordCount * 1000.0 / processingTime) + " rec/s");

            inputData.unpersist();

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            sc.stop();
        }
    }
}