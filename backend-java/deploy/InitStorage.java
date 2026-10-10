import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;

class InitStorage {
    public static void main(String[] args) throws Exception {
        var client = MinioClient.builder()
            .endpoint(System.getenv("MINIO_ENDPOINT"))
            .credentials(System.getenv("MINIO_ACCESS_KEY"), System.getenv("MINIO_SECRET_KEY"))
            .build();
        var bucket = System.getenv("MINIO_BUCKET");
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        System.out.println("Storage bucket ready: " + bucket);
    }
}
