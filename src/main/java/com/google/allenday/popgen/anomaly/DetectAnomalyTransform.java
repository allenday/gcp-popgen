package com.google.allenday.popgen.anomaly;

import com.google.allenday.genomics.core.batch.PreparingTransform;
import com.google.allenday.genomics.core.model.FileWrapper;
import com.google.allenday.genomics.core.model.SampleMetaData;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.transforms.Filter;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptors;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

public class DetectAnomalyTransform extends PreparingTransform {

    private String resultBucket;
    private String anomalyOutputPath;
    private RecognizePairedReadsWithAnomalyFn recognizePairedReadsWithAnomalyFn;

    public DetectAnomalyTransform(String resultBucket, String anomalyOutputPath, RecognizePairedReadsWithAnomalyFn recognizePairedReadsWithAnomalyFn) {
        this.resultBucket = resultBucket;
        this.anomalyOutputPath = anomalyOutputPath;
        this.recognizePairedReadsWithAnomalyFn = recognizePairedReadsWithAnomalyFn;
    }

    public DetectAnomalyTransform(@Nullable String name, String resultBucket, String anomalyOutputPath, RecognizePairedReadsWithAnomalyFn recognizePairedReadsWithAnomalyFn) {
        super(name);
        this.resultBucket = resultBucket;
        this.anomalyOutputPath = anomalyOutputPath;
        this.recognizePairedReadsWithAnomalyFn = recognizePairedReadsWithAnomalyFn;
    }

    @Override
    public PCollection<KV<SampleMetaData, List<FileWrapper>>> expand(PCollection<KV<SampleMetaData, List<FileWrapper>>> input) {
        PCollection<KV<SampleMetaData, List<FileWrapper>>> recognizeAnomaly = input
                .apply("Recognize anomaly", ParDo.of(recognizePairedReadsWithAnomalyFn));


        MapElements.into(TypeDescriptors.strings()).via((KV<SampleMetaData, List<FileWrapper>> kvInput) ->
                Optional.ofNullable(kvInput.getKey())
                        .map(geneSampleMetaData -> geneSampleMetaData.getComment() + "," + geneSampleMetaData.getSrcRawMetaData())
                        .orElse("")
        );

        recognizeAnomaly
                .apply(Filter.by(element -> element.getValue().size() == 0))
                .apply(MapElements.into(TypeDescriptors.strings()).via((KV<SampleMetaData, List<FileWrapper>> kvInput) ->
                        Optional.ofNullable(kvInput.getKey())
                                .map(geneSampleMetaData -> geneSampleMetaData.getComment() + "," + geneSampleMetaData.getSrcRawMetaData())
                                .orElse("")
                ))
                .apply(TextIO.write().withNumShards(1).to(String.format("gs://%s/%s", resultBucket, anomalyOutputPath)));

        return recognizeAnomaly.apply(Filter.by(element -> element.getValue().size() > 0));
    }
}
