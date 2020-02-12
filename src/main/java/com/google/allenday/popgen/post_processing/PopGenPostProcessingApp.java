package com.google.allenday.popgen.post_processing;

import com.google.allenday.genomics.core.csv.ParseSourceCsvTransform;
import com.google.allenday.genomics.core.model.FileWrapper;
import com.google.allenday.genomics.core.model.SampleMetaData;
import com.google.allenday.genomics.core.model.SraSampleId;
import com.google.allenday.genomics.core.parts_processing.*;
import com.google.allenday.genomics.core.pipeline.PipelineSetupUtils;
import com.google.allenday.genomics.core.processing.align.AlignFn;
import com.google.allenday.genomics.core.processing.dv.DeepVariantFn;
import com.google.allenday.genomics.core.processing.sam.CreateBamIndexFn;
import com.google.allenday.genomics.core.processing.sam.MergeFn;
import com.google.allenday.genomics.core.processing.sam.SortFn;
import com.google.allenday.genomics.core.utils.NameProvider;
import com.google.cloud.storage.BlobId;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.beam.runners.direct.DirectRunner;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.*;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.sdk.values.TypeDescriptors;

import java.util.List;
import java.util.Objects;

public class PopGenPostProcessingApp {

    private final static String JOB_NAME_PREFIX = "pop-gen-finalise-%s--";

    public enum PostProcessingType {
        FINALIZE_ALIGN,
        FINALIZE_SORT,
        FINALIZE_MERGE,
        FINALIZE_INDEX,
        FINALIZE_DV,
        FINALIZE_VCF_TO_BQ,
        ANALYZE_COMPLETION_STATUS
    }

    public static void main(String[] args) {

        FinalizePopGenProcessingOptions pipelineOptions = PipelineOptionsFactory.fromArgs(args)
                .withValidation()
                .as(FinalizePopGenProcessingOptions.class);
        PipelineSetupUtils.prepareForInlineAlignment(pipelineOptions);

        Injector injector = Guice.createInjector(new FinalizePopGenProcessingAppModule(pipelineOptions));

        NameProvider nameProvider = injector.getInstance(NameProvider.class);
        pipelineOptions.setJobName(nameProvider.buildJobName(
                String.format(JOB_NAME_PREFIX, pipelineOptions.getPostProcessingType().name()),
                pipelineOptions.getSraSamplesToFilter()));

        Pipeline pipeline = Pipeline.create(pipelineOptions);

        StagingPathsBulder stagingPathsBuilder = injector.getInstance(StagingPathsBulder.class);

        if (pipelineOptions.getPostProcessingType() == PostProcessingType.FINALIZE_VCF_TO_BQ) {
            pipeline
                    .apply(Create.of(BlobId.of(stagingPathsBuilder.getStagingBucket(),
                            stagingPathsBuilder.buildVcfDirPath())))
                    .apply(injector.getInstance(VcfToBqBatchTransform.class));
        } else {

            PCollection<KV<SampleMetaData, List<FileWrapper>>> parsedData = pipeline
                    .apply("Parse data", injector.getInstance(ParseSourceCsvTransform.class));

            if (pipelineOptions.getPostProcessingType() == PostProcessingType.FINALIZE_ALIGN) {
                parsedData.apply(ParDo.of(injector.getInstance(PrepareAlignNotProcessedFn.class)))
                        .apply("Align", ParDo.of(injector.getInstance(AlignFn.class)));
            } else if (pipelineOptions.getPostProcessingType() == PostProcessingType.FINALIZE_SORT) {
                parsedData.apply(ParDo.of(injector.getInstance(PrepareSortNotProcessedFn.class)))
                        .apply("Sort", ParDo.of(injector.getInstance(SortFn.class)));
            } else if (pipelineOptions.getPostProcessingType() == PostProcessingType.FINALIZE_MERGE) {
                parsedData
                        .apply(new GroupBySraSample())
                        .apply(ParDo.of(injector.getInstance(PrepareMergeNotProcessedFn.class)))
                        .apply("Merge", ParDo.of(injector.getInstance(MergeFn.class)));
            } else if (pipelineOptions.getPostProcessingType() == PostProcessingType.FINALIZE_INDEX) {
                parsedData
                        .apply(new GroupBySraSample())
                        .apply(ParDo.of(injector.getInstance(PrepareIndexNotProcessedFn.class)))
                        .apply("Index", ParDo.of(injector.getInstance(CreateBamIndexFn.class)));
            } else if (pipelineOptions.getPostProcessingType() == PostProcessingType.FINALIZE_DV) {
                parsedData
                        .apply(new GroupBySraSample())
                        .apply(ParDo.of(injector.getInstance(PrepareDvNotProcessedFn.class)))
                        .apply("Variant Calling", ParDo.of(injector.getInstance(DeepVariantFn.class)));

            } else if (pipelineOptions.getPostProcessingType() == PostProcessingType.ANALYZE_COMPLETION_STATUS) {
                parsedData
                        .apply(MapElements.into(TypeDescriptors.kvs(TypeDescriptor.of(SraSampleId.class),
                                TypeDescriptors.kvs(TypeDescriptor.of(SampleMetaData.class),
                                        TypeDescriptors.lists(TypeDescriptor.of(FileWrapper.class)))))
                                .via((KV<SampleMetaData, List<FileWrapper>> kvInput) ->
                                        KV.of(Objects.requireNonNull(kvInput.getKey()).getSraSample(),
                                                KV.of(kvInput.getKey(), kvInput.getValue()))))
                        .apply(GroupByKey.create())
                        .apply(ParDo.of(injector.getInstance(CheckExistenceFn.class)))
                        .apply(ToString.elements())
                        .apply(TextIO.write().withNumShards(1).to(stagingPathsBuilder.getExistenceCsvUri()));
            }
        }

        PipelineResult run = pipeline.run();
        if (pipelineOptions.getRunner().getName().equals(DirectRunner.class.getName())) {
            run.waitUntilFinish();
        }
    }

    public static class GroupBySraSample extends PTransform<PCollection<KV<SampleMetaData, List<FileWrapper>>>, PCollection<KV<SraSampleId, Iterable<SampleMetaData>>>> {
        @Override
        public PCollection<KV<SraSampleId, Iterable<SampleMetaData>>> expand(PCollection<KV<SampleMetaData, List<FileWrapper>>> input) {
            return input.apply(MapElements.into(TypeDescriptors.kvs(TypeDescriptor.of(SraSampleId.class), TypeDescriptor.of(SampleMetaData.class)))
                    .via((KV<SampleMetaData, List<FileWrapper>> kvInput) -> KV.of(Objects.requireNonNull(kvInput.getKey()).getSraSample(), kvInput.getKey())))
                    .apply(GroupByKey.create());
        }
    }
}
