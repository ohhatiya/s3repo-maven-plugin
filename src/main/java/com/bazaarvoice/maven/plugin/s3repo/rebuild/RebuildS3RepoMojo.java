package com.bazaarvoice.maven.plugin.s3repo.rebuild;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.bazaarvoice.maven.plugin.s3repo.S3RepositoryPath;
import com.bazaarvoice.maven.plugin.s3repo.support.LocalYumRepoFacade;
import com.bazaarvoice.maven.plugin.s3repo.util.ExtraFileUtils;
import com.bazaarvoice.maven.plugin.s3repo.util.ExtraIOUtils;
import com.bazaarvoice.maven.plugin.s3repo.util.S3Utils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.io.InputStreamFacade;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Mojo (name = "rebuild-repo")
public final class RebuildS3RepoMojo extends AbstractMojo {

    /** Staging directory. This is where we will recreate the relevant *bucket* files. */
    @Parameter(property = "s3repo.stagingDirectory", defaultValue = "${project.build.directory}/s3repo")
    private File stagingDirectory;

    /**
     * The s3 path to the root of the target repository.
     * These are all valid values:
     *      "s3://Bucket1/Repo1"
     *      "/Bucket/Repo1"
     */
    @Parameter (property = "s3repo.repositoryPath", required = true)
    private String s3RepositoryPath;

    @Parameter(property = "s3repo.accessKey", required = true)
    private String s3AccessKey;

    @Parameter(property = "s3repo.secretKey", required = true)
    private String s3SecretKey;

    /** Do not try to validate the current repository metadata before recreating the repository. */
    @Parameter(property = "s3repo.doNotValidate", defaultValue = "false")
    private boolean doNotValidate;

    @Parameter(property = "s3repo.removeOldSnapshots", defaultValue = "false")
    private boolean removeOldSnapshots;

    /** Execute all steps up to and excluding the upload to the S3. This can be set to true to perform a "dryRun" execution. */
    @Parameter(property = "s3repo.doNotUpload", defaultValue = "false")
    private boolean doNotUpload;

    /** The createrepo executable. */
    @Parameter(property = "s3repo.createrepo", defaultValue = "createrepo")
    private String createrepo;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        RebuildContext context = new RebuildContext();

        context.setS3Session(createS3Client());
        context.setS3RepositoryPath(parseS3RepositoryPath());
        context.setLocalYumRepo(determineLocalYumRepo(context.getS3RepositoryPath()));

        // always clean staging directory
        ExtraFileUtils.createOrCleanDirectory(stagingDirectory);

        // download entire repository
        pullEntireRepository(context);
        // perform some checks to ensure repository is as expected if doNotValidate = false
        maybeValidateRepository(context);
        // remove old snapshots if removeOldSnapshots = true
        maybeRemoveOldSnapshots(context);
        // rebuild -- rerun createrepo
        rebuildRepo(context);
        // upload repository and delete old snapshots etc. if doNotUpload = false
        maybeUploadRepository(context);
    }

    private void maybeUploadRepository(RebuildContext context) throws MojoExecutionException {
        if (doNotUpload) {
            getLog().info("Per configuration, not uploading built repository to S3.");
            return;
        }
        final String targetBucket = context.getS3RepositoryPath().getBucketName();
        AmazonS3 s3Session = context.getS3Session();
        for (File toUpload : ExtraIOUtils.listAllFiles(stagingDirectory)) {
            String relativizedPath = ExtraIOUtils.relativize(stagingDirectory, toUpload);
            // replace *other* file separators with S3-style file separators and strip first & last separator
            relativizedPath = relativizedPath.replaceAll("\\\\", "/").replaceAll("^/", "").replaceAll("/$", "");
            String key = relativizedPath;
            getLog().info("Uploading " + toUpload.getName() + " to s3://" + targetBucket + "/" + key + "...");
            PutObjectRequest putObjectRequest = new PutObjectRequest(targetBucket, key, toUpload);
            s3Session.putObject(putObjectRequest);
        }
        // and finally, delete any bucket keys we wish to remove (e.g., old snaphots)
        for (String bucketKeyToDelete : context.getSnapshotBucketKeysToDelete()) {
            getLog().info("Deleting old snapshot '" + bucketKeyToDelete + "' from S3...");
            context.getS3Session().deleteObject(context.getS3RepositoryPath().getBucketName(), bucketKeyToDelete);
        }
    }

    private void rebuildRepo(RebuildContext context) throws MojoExecutionException {
        getLog().info("Rebuilding repo...");
        context.getLocalYumRepo().createRepo();
    }

    private void maybeRemoveOldSnapshots(RebuildContext context) throws MojoExecutionException {
        if (removeOldSnapshots) {
            getLog().info("Removing old snapshots...");
            Map<String, List<SnapshotDescription>> snapshots = context.getBucketKeyPrefixToSnapshots();
            for (String snapshotKeyPrefix : snapshots.keySet()) {
                List<SnapshotDescription> snapshotsRepresentingSameInstallable = snapshots.get(snapshotKeyPrefix);
                if (snapshotsRepresentingSameInstallable.size() > 1) {
                    // if there's more than one snapshot for a given installable, then we have cleanup to do
                    Collections.sort(snapshotsRepresentingSameInstallable, new Comparator<SnapshotDescription>() {
                        @Override
                        public int compare(SnapshotDescription left, SnapshotDescription right) {
                            // IMPORTANT: this ensures that *newer* artifacts are ordered first
                            return right.getLastModified().compareTo(left.getLastModified());
                        }
                    });
                    // start with *second* artifact; delete it and everything after it (these are the older artifacts)
                    for (int i = 1; i < snapshotsRepresentingSameInstallable.size(); ++i) {
                        SnapshotDescription toDelete = snapshotsRepresentingSameInstallable.get(i);
                        getLog().info("Deleting old snapshot '" + toDelete.getBucketKey() + "', locally...");
                        // delete object locally so createrepo step doesn't pick it up
                        context.getLocalYumRepo().deleteFile(toDelete.getBucketKey());
                        // we'll also delete the object from s3 but only after we upload the repository metadata
                        // (so we don't confuse any repo clients who are reading the current repo metadata)
                        context.addBucketKeyOfSnapshotToDelete(toDelete.getBucketKey());
                    }
                }
            }
        }
    }

    private void maybeValidateRepository(RebuildContext context) throws MojoExecutionException {
        if (doNotValidate) {
            return;
        }
        getLog().info("Validating downloaded repository...");
        LocalYumRepoFacade localYumRepo = context.getLocalYumRepo();
        if (!localYumRepo.isRepoDataExists()) {
            throw new MojoExecutionException("Repository does not exist!");
        }
        // list of files (repo-relative paths)
        List<String> fileList = localYumRepo.parseFileListFromRepoMetadata();
        for (String file : fileList) {
            if (!localYumRepo.hasFile(file)) {
                throw new MojoExecutionException("Repository metadata declared file " + file + " but the file does not exist.");
            }
        }
    }

    /** Create a {@link com.bazaarvoice.maven.plugin.s3repo.support.LocalYumRepoFacade} which will allow us to query and operate on a local (on-disk) yum repository. */
    private LocalYumRepoFacade determineLocalYumRepo(S3RepositoryPath s3RepositoryPath) {
        return new LocalYumRepoFacade(
                s3RepositoryPath.hasBucketRelativeFolder()
                        ? new File(stagingDirectory, s3RepositoryPath.getBucketRelativeFolder())
                        : stagingDirectory, createrepo, getLog());
    }

    private AmazonS3Client createS3Client() {
        return new AmazonS3Client(new BasicAWSCredentials(s3AccessKey, s3SecretKey));
    }

    /** Download the entire repository. (Also adds SNAPSHOT metadata to the provided <code>context</code>.) */
    private void pullEntireRepository(RebuildContext context) throws MojoExecutionException {
        getLog().info("Downloading entire repository...");
        S3RepositoryPath s3RepositoryPath = context.getS3RepositoryPath();
        ListObjectsRequest listObjectsRequest = new ListObjectsRequest()
                .withBucketName(s3RepositoryPath.getBucketName());
        String prefix = ""; // capture prefix for debug logging
        if (s3RepositoryPath.hasBucketRelativeFolder()) {
            prefix = s3RepositoryPath.getBucketRelativeFolder() + "/";
            listObjectsRequest.withPrefix(prefix);
        }
        List<S3ObjectSummary> result = S3Utils.listAllObjects(context.getS3Session(), listObjectsRequest);
        getLog().debug("Found " + result.size() + " objects in bucket '" + s3RepositoryPath.getBucketName()
                + "' with prefix '" + s3RepositoryPath.getBucketRelativeFolder() + "/" + "'...");
        for (S3ObjectSummary summary : result) {
            getLog().info("Downloading " + summary.getKey() + " from S3...");
            // for every item in the repository, add it to our snapshot metadata if it's a snapshot artifact
            maybeAddSnapshotMetadata(summary, context);
            final S3Object object = context.getS3Session()
                    .getObject(new GetObjectRequest(s3RepositoryPath.getBucketName(), summary.getKey()));
            try {
                File targetFile =
                        new File(stagingDirectory, /*assume object key is bucket-relative path to filename with extension*/summary.getKey());
                // target file's directories will be created if they don't already exist
                FileUtils.copyStreamToFile(new InputStreamFacade() {
                    @Override
                    public InputStream getInputStream() throws IOException {
                        return object.getObjectContent();
                    }
                }, targetFile);
            } catch (IOException e) {
                throw new MojoExecutionException("failed to downlod object from s3: " + summary.getKey(), e);
            }
        }
    }

    private void maybeAddSnapshotMetadata(S3ObjectSummary summary, RebuildContext context) {
        final int lastSlashIndex = summary.getKey().lastIndexOf("/");
        // determine the path to the file (excluding the filename iteself); this path may be empty, otherwise it contains
        // a "/" suffix
        final String path = lastSlashIndex > 0 ? summary.getKey().substring(0, lastSlashIndex + 1) : "";
        // determine the file name (without any directory path elements)
        final String fileName = lastSlashIndex > 0 ? summary.getKey().substring(lastSlashIndex + 1) : summary.getKey();
        final int snapshotIndex = fileName.indexOf("SNAPSHOT");
        if (snapshotIndex > 0) { // heuristic: we have a SNAPSHOT artifact here
            final String prefixWithoutPath = fileName.substring(0, snapshotIndex);
            final String bucketKeyPrefix = path + prefixWithoutPath;
            getLog().debug("Making note of snapshot '" + summary.getKey() + "'; using prefix = " + bucketKeyPrefix);
            // ASSERT: bucketKeyPrefix is *full path* of bucket key up to and excluding the SNAPSHOT string and anything after it.
            context.addSnapshotDescription(new SnapshotDescription(bucketKeyPrefix, summary.getKey(), summary.getLastModified()));
        }
    }

    private S3RepositoryPath parseS3RepositoryPath() throws MojoExecutionException {
        try {
            S3RepositoryPath parsed = S3RepositoryPath.parse(s3RepositoryPath);
            if (parsed.hasBucketRelativeFolder()) {
                getLog().info("Using bucket '" + parsed.getBucketName() + "' and folder '" + parsed.getBucketRelativeFolder() + "' as repository...");
            } else {
                getLog().info("Using bucket '" + parsed.getBucketName() + "' as repository...");
            }
            return parsed;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to parse S3 repository path: " + s3RepositoryPath, e);
        }
    }

}
