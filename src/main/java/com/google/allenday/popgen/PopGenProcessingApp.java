package com.google.allenday.popgen;

import com.google.allenday.genomics.core.batch.BatchProcessingPipelineOptions;
import com.google.allenday.genomics.core.csv.ParseSourceCsvTransform;
import com.google.allenday.genomics.core.model.BamWithIndexUris;
import com.google.allenday.genomics.core.model.SamRecordsMetadaKey;
import com.google.allenday.genomics.core.pipeline.PipelineSetupUtils;
import com.google.allenday.genomics.core.processing.AlignAndSamProcessingTransform;
import com.google.allenday.genomics.core.processing.SplitFastqIntoBatches;
import com.google.allenday.genomics.core.processing.variantcall.VariantCallingTransform;
import com.google.allenday.genomics.core.processing.vcf_to_bq.PrepareAndExecuteVcfToBqTransform;
import com.google.allenday.genomics.core.reference.ReferenceDatabaseSource;
import com.google.allenday.genomics.core.utils.NameProvider;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;


public class PopGenProcessingApp {

    private final static String JOB_NAME_PREFIX = "pop-gen-processing-";

    public static void main(String[] args) {
        BatchProcessingPipelineOptions pipelineOptions = PipelineOptionsFactory.fromArgs(args)
                .withValidation()
                .as(BatchProcessingPipelineOptions.class);
        PipelineSetupUtils.prepareForInlineAlignment(pipelineOptions);

        Injector injector = Guice.createInjector(new PopGenProcessingAppModule(pipelineOptions));

        NameProvider nameProvider = injector.getInstance(NameProvider.class);
        pipelineOptions.setJobName(nameProvider.buildJobName(JOB_NAME_PREFIX, pipelineOptions.getSraSamplesToFilter()));

        Pipeline pipeline = Pipeline.create(pipelineOptions);

        PCollection<KV<SamRecordsMetadaKey, KV<ReferenceDatabaseSource, BamWithIndexUris>>> bamWithIndexUris = pipeline
                .apply("Parse data", injector.getInstance(ParseSourceCsvTransform.class))
                .apply("Split large FASTQ into chunks", injector.getInstance(SplitFastqIntoBatches.class))
                .apply("Align reads and prepare for DV", injector.getInstance(AlignAndSamProcessingTransform.class));

        if (pipelineOptions.getWithVariantCalling()) {
            PCollection<KV<SamRecordsMetadaKey, KV<String, String>>> vcfResults = bamWithIndexUris
                    .apply("Variant Calling", injector.getInstance(VariantCallingTransform.class));

            if (pipelineOptions.getWithExportVcfToBq()) {
                vcfResults
                        .apply("Export to BigQuery", injector.getInstance(PrepareAndExecuteVcfToBqTransform.class));
            }
        }

        pipeline.run();
    }

}
