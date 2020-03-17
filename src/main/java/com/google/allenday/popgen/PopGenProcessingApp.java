package com.google.allenday.popgen;

import com.google.allenday.genomics.core.batch.BatchProcessingPipelineOptions;
import com.google.allenday.genomics.core.csv.ParseSourceCsvTransform;
import com.google.allenday.genomics.core.model.BamWithIndexUris;
import com.google.allenday.genomics.core.model.SraSampleIdReferencePair;
import com.google.allenday.genomics.core.pipeline.PipelineSetupUtils;
import com.google.allenday.genomics.core.processing.AlignAndPostProcessTransform;
import com.google.allenday.genomics.core.processing.dv.DeepVariantFn;
import com.google.allenday.genomics.core.processing.vcf_to_bq.VcfToBqFn;
import com.google.allenday.genomics.core.reference.ReferenceDatabaseSource;
import com.google.allenday.genomics.core.utils.NameProvider;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptors;

import java.util.Objects;


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

        PCollection<KV<SraSampleIdReferencePair, KV<ReferenceDatabaseSource, BamWithIndexUris>>> bamWithIndexUris = pipeline
                .apply("Parse data", injector.getInstance(ParseSourceCsvTransform.class))
                .apply("Align reads and prepare for FINALIZE_DV", injector.getInstance(AlignAndPostProcessTransform.class));

        if (pipelineOptions.getWithVariantCalling()) {
            PCollection<KV<SraSampleIdReferencePair, String>> vcfResults = bamWithIndexUris
                    .apply("Variant Calling", ParDo.of(injector.getInstance(DeepVariantFn.class)));

            if (pipelineOptions.getWithExportVcfToBq()) {
                vcfResults
                        .apply("Prepare to VcfToBq transform", MapElements.into(TypeDescriptors.kvs(
                                TypeDescriptors.strings(), TypeDescriptors.strings()
                        )).via((KV<SraSampleIdReferencePair, String> kvInput) ->
                                KV.of(Objects.requireNonNull(kvInput.getKey()).getReferenceName(), kvInput.getValue())))
                        .apply("Export to BigQuery", ParDo.of(injector.getInstance(VcfToBqFn.class)));
            }
        }

        pipeline.run();
    }

}
