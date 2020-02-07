package com.google.allenday.nanostream.rice.parts_processing;

import com.google.allenday.genomics.core.batch.BatchProcessingPipelineOptions;
import com.google.allenday.genomics.core.csv.ParseSourceCsvTransform;
import com.google.allenday.genomics.core.io.FileUtils;
import com.google.allenday.genomics.core.model.FileWrapper;
import com.google.allenday.genomics.core.model.SampleMetaData;
import com.google.allenday.genomics.core.model.SraSampleId;
import com.google.allenday.genomics.core.parts_processing.PrepareMergeNotProcessedFn;
import com.google.allenday.genomics.core.pipeline.GenomicsOptions;
import com.google.allenday.genomics.core.pipeline.PipelineSetupUtils;
import com.google.allenday.genomics.core.processing.sam.CreateBamIndexFn;
import com.google.allenday.genomics.core.processing.sam.MergeFn;
import com.google.allenday.genomics.core.utils.NameProvider;
import com.google.allenday.nanostream.rice.NanostreamRiceModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Names;
import org.apache.beam.runners.direct.DirectRunner;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.values.KV;

import java.util.List;

public class NanostreamBatchAppMerge {

    private final static String JOB_NAME_PREFIX = "nanostream-cannabis-merge-";

    public static void main(String[] args) {

        BatchProcessingPipelineOptions pipelineOptions = PipelineOptionsFactory.fromArgs(args)
                .withValidation()
                .as(BatchProcessingPipelineOptions.class);
        PipelineSetupUtils.prepareForInlineAlignment(pipelineOptions);

        Injector injector = Guice.createInjector(new NanostreamRiceModule.Builder()
                .setFromOptions(pipelineOptions)
                .build());

        NameProvider nameProvider = injector.getInstance(NameProvider.class);
        pipelineOptions.setJobName(nameProvider.buildJobName(JOB_NAME_PREFIX, pipelineOptions.getSraSamplesToFilter()));

        Pipeline pipeline = Pipeline.create(pipelineOptions);

        final String stagedBucket = injector.getInstance(Key.get(String.class, Names.named("resultBucket")));
        final String outputDir = injector.getInstance(Key.get(String.class, Names.named("outputDir")));
        final String stagedDir = outputDir + "staged/";

        GenomicsOptions genomicsOptions = injector.getInstance(GenomicsOptions.class);
        List<String> geneReferences = genomicsOptions.getGeneReferences();

        FileUtils fileUtils = injector.getInstance(FileUtils.class);
        String alignedFilePattern = stagedDir + "result_aligned_bam/%s_%s.sam";
        String sortedFilePattern = stagedDir + "result_sorted_bam/%s_%s.sorted.bam";
        String mergedFilePattern = stagedDir + "result_merged_bam/%s_%s.merged.sorted.bam";
        String indexFilePattern = stagedDir + "result_merged_bam/%s_%s.merged.sorted.bam.bai";
        String vcfFilePattern = stagedDir + "result_dv/%s_%s/%s_%s.vcf";
        String vcfToBqProcessedListFile = stagedDir + "vcf_to_bq/vcf_to_bq_processed.csv";

        pipeline

                .apply("Parse data", injector.getInstance(ParseSourceCsvTransform.class))
                .apply(MapElements.via(new SimpleFunction<KV<SampleMetaData, List<FileWrapper>>, KV<SraSampleId, SampleMetaData>>() {
                    @Override
                    public KV<SraSampleId, SampleMetaData> apply(KV<SampleMetaData, List<FileWrapper>> input) {

                        return KV.of(input.getKey().getSraSample(), input.getKey());
                    }
                }))
                .apply(GroupByKey.create())
                .apply(ParDo.of(new PrepareMergeNotProcessedFn(fileUtils, geneReferences, stagedBucket,
                        mergedFilePattern, sortedFilePattern)))
                .apply(ParDo.of(injector.getInstance(MergeFn.class)))
                .apply(ParDo.of(injector.getInstance(CreateBamIndexFn.class)))

        ;

        PipelineResult run = pipeline.run();
        if (pipelineOptions.getRunner().getName().equals(DirectRunner.class.getName())) {
            run.waitUntilFinish();
        }
    }
}
