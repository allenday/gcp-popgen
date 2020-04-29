package com.google.allenday.popgen.anomaly;

import com.google.allenday.genomics.core.io.FileUtils;
import com.google.allenday.genomics.core.io.GCSService;
import com.google.allenday.genomics.core.model.FileWrapper;
import com.google.allenday.genomics.core.model.Instrument;
import com.google.allenday.genomics.core.model.SampleMetaData;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.KV;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class RecognizePairedReadsWithAnomalyFn extends DoFn<KV<SampleMetaData, List<FileWrapper>>, KV<SampleMetaData, List<FileWrapper>>> {

    private Logger LOG = LoggerFactory.getLogger(RecognizePairedReadsWithAnomalyFn.class);

    private String srcBucket;
    private GCSService gcsService;
    private FileUtils fileUtils;
    private final boolean tryToFindWithSuffixMistake;

    public RecognizePairedReadsWithAnomalyFn(String stagedBucket, FileUtils fileUtils) {
        this(stagedBucket, fileUtils, false);
    }

    public RecognizePairedReadsWithAnomalyFn(String stagedBucket, FileUtils fileUtils, boolean tryToFindWithSuffixMistake) {
        this.srcBucket = stagedBucket;
        this.fileUtils = fileUtils;
        this.tryToFindWithSuffixMistake = tryToFindWithSuffixMistake;
    }

    @Setup
    public void setUp() {
        gcsService = GCSService.initialize(fileUtils);
    }

    @ProcessElement
    public void processElement(ProcessContext c) {
        KV<SampleMetaData, List<FileWrapper>> input = c.element();
        LOG.info(String.format("RecognizePairedReadsWithAnomalyFn %s", input.toString()));

        SampleMetaData geneSampleMetaData = input.getKey();
        List<FileWrapper> originalGeneDataList = input.getValue();

        if (geneSampleMetaData == null || originalGeneDataList == null) {
            LOG.info("Data error {}, {}", geneSampleMetaData, originalGeneDataList);
            return;
        }
        if (Arrays.stream(Instrument.values()).map(Enum::name).noneMatch(instrumentName -> instrumentName.equals(geneSampleMetaData.getPlatform()))) {
            geneSampleMetaData.setComment(String.format("Unknown INSTRUMENT: %s", geneSampleMetaData.getPlatform()));
            c.output(KV.of(geneSampleMetaData, Collections.emptyList()));
            return;
        }

        try {
            List<FileWrapper> checkedGeneDataList = new ArrayList<>();
            if (originalGeneDataList.size() > 0) {
                originalGeneDataList.stream()
                        .findFirst()
                        .map(FileWrapper::getBlobUri)
                        .map(blobUri -> gcsService.getBlobIdFromUri(blobUri))
                        .map(BlobId::getName)
                        .map(blobName -> fileUtils.getDirFromPath(blobName))
                        .ifPresent(dirPrefix -> {
                            String filesNameBaseToSearch = dirPrefix + geneSampleMetaData.getRunId();

                            List<Blob> blobs = StreamSupport
                                    .stream(gcsService.getBlobsWithPrefix(srcBucket, filesNameBaseToSearch)
                                            .spliterator(), false).collect(Collectors.toList());

                            if (blobs.size() != originalGeneDataList.size()) {
                                if (tryToFindWithSuffixMistake && blobs.size() == 1 && originalGeneDataList.size() == 2) {
                                    List<Blob> blobsToSearch = StreamSupport.stream(gcsService.getBlobsWithPrefix(srcBucket, dirPrefix)
                                            .spliterator(), false).collect(Collectors.toList());

                                    originalGeneDataList
                                            .forEach(fileWrapper -> {
                                                boolean runBlobExists = gcsService.isExists(gcsService.getBlobIdFromUri(fileWrapper.getBlobUri()));
                                                if (runBlobExists) {
                                                    checkedGeneDataList.add(fileWrapper);
                                                } else {
                                                    searchWithSuffixMistake(geneSampleMetaData, fileWrapper, blobsToSearch, checkedGeneDataList);
                                                }
                                            });
                                } else {
                                    geneSampleMetaData.setComment("Wrong files number for runId");
                                    logAnomaly(blobs, geneSampleMetaData);
                                }
                            } else {
                                List<FileWrapper> fileWrappers = blobs.stream().map(blob -> {
                                    String uriFromBlob = gcsService.getUriFromBlob(blob.getBlobId());
                                    String name = fileUtils.getFilenameFromPath(uriFromBlob);
                                    FileWrapper fileWrapper = FileWrapper.fromBlobUri(uriFromBlob, name);

                                    String[] parts = name.split("_");
                                    int suffix = Integer.parseInt(parts[parts.length - 1].split("\\.")[0]);
                                    return Pair.with(fileWrapper, suffix);
                                }).sorted(Comparator.comparing(Pair::getValue1)).map(Pair::getValue0).collect(Collectors.toList());
                                checkedGeneDataList.addAll(fileWrappers);
                            }
                            if (originalGeneDataList.size() == checkedGeneDataList.size()) {
                                c.output(KV.of(geneSampleMetaData, checkedGeneDataList));
                            } else {
                                geneSampleMetaData.setComment(geneSampleMetaData.getComment() + String.format(" (%d/%d)", checkedGeneDataList.size(), originalGeneDataList.size()));
                                c.output(KV.of(geneSampleMetaData, Collections.emptyList()));
                            }
                        });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void searchWithSuffixMistake(SampleMetaData geneSampleMetaData, FileWrapper fileWrapper,
                                         List<Blob> blobs, List<FileWrapper> checkedGeneDataList) {
        String indexToSearch = fileWrapper.getFileName().split("\\.")[0].split("_")[1];
        if (Integer.parseInt(indexToSearch) != 2) {
            geneSampleMetaData.setComment("Already tried with _1 paired index");
            return;
        }

        LOG.info(String.format("Blob %s doesn't exist. Trying to find blob with other SRA for %s", fileWrapper.getBlobUri(), geneSampleMetaData.toString()));
        Optional<Blob> blobOpt = blobs.stream().filter(blob -> {
            boolean containsIndex = blob.getName().contains(String.format("_%s", indexToSearch));
            if (blobs.size() == 2) {
                return containsIndex;
            } else {
                boolean containsIndexFirst = blob.getName().contains(String.format("_%s", 1));
                int runInt = Integer.parseInt(blob.getName()
                        .substring(blob.getName().lastIndexOf('/') + 1).split("_")[0]
                        .substring(3));
                int serchedRunInt = Integer.parseInt(geneSampleMetaData.getRunId().substring(3));
                return Math.abs(serchedRunInt - runInt) == 1 && !containsIndexFirst && containsIndex;
            }
        }).findFirst();
        if (blobOpt.isPresent()) {
            LOG.info(String.format("Found: %s", blobOpt.get().getName()));
            String fileUri = String.format("gs://%s/%s", blobOpt.get().getBucket(), blobOpt.get().getName());
            String fileName = fileUtils.getFilenameFromPath(fileUri);
            checkedGeneDataList.add(FileWrapper.fromBlobUri(fileUri, fileName));
        } else {
            logAnomaly(blobs, geneSampleMetaData);
            geneSampleMetaData.setComment("File not found");
        }
    }

    private void logAnomaly(List<Blob> blobs, SampleMetaData geneSampleMetaData) {
        LOG.info(String.format("Anomaly: %s, %s, %s, blobs %s",
                geneSampleMetaData.getCenterName(),
                geneSampleMetaData.getSraSample(),
                geneSampleMetaData.getRunId(),
                blobs.stream().map(BlobInfo::getName).collect(Collectors.joining(", "))));
    }
}
