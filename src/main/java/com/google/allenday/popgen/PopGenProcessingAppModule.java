package com.google.allenday.popgen;

import com.google.allenday.genomics.core.batch.BatchProcessingModule;
import com.google.allenday.genomics.core.batch.BatchProcessingPipelineOptions;
import com.google.allenday.genomics.core.batch.PreparingTransform;
import com.google.allenday.genomics.core.io.FileUtils;
import com.google.allenday.genomics.core.io.UriProvider;
import com.google.allenday.genomics.core.pipeline.GenomicsOptions;
import com.google.allenday.genomics.core.processing.align.AlignService;
import com.google.allenday.genomics.core.utils.NameProvider;
import com.google.allenday.popgen.anomaly.DetectAnomalyTransform;
import com.google.allenday.popgen.anomaly.RecognizePairedReadsWithAnomalyFn;
import com.google.allenday.popgen.io.DefaultUriProvider;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import java.util.List;


public class PopGenProcessingAppModule extends BatchProcessingModule {

    public PopGenProcessingAppModule(String srcBucket,
                                     String inputCsvUri,
                                     List<String> sraSamplesToFilter,
                                     List<String> sraSamplesToSkip,
                                     String project,
                                     String region,
                                     GenomicsOptions genomicsOptions) {
        super(srcBucket, inputCsvUri, sraSamplesToFilter, sraSamplesToSkip, project, region, genomicsOptions);
    }

    public PopGenProcessingAppModule(BatchProcessingPipelineOptions batchProcessingPipelineOptions) {
        this(
                batchProcessingPipelineOptions.getSrcBucket(),
                batchProcessingPipelineOptions.getInputCsvUri(),
                batchProcessingPipelineOptions.getSraSamplesToFilter(),
                batchProcessingPipelineOptions.getSraSamplesToSkip(),
                batchProcessingPipelineOptions.getProject(),
                batchProcessingPipelineOptions.getRegion(),
                GenomicsOptions.fromAlignerPipelineOptions(batchProcessingPipelineOptions)
        );
    }

    @Provides
    @Singleton
    public RecognizePairedReadsWithAnomalyFn provideRecognizePairedReadsWithAnomalyFn(FileUtils fileUtils) {
        return new RecognizePairedReadsWithAnomalyFn(srcBucket, fileUtils);
    }

    @Provides
    @Singleton
    public PreparingTransform provideGroupByPairedReadsAndFilter(RecognizePairedReadsWithAnomalyFn recognizePairedReadsWithAnomalyFn,
                                                                 NameProvider nameProvider) {
        return new DetectAnomalyTransform("Filter anomaly and prepare for processing", genomicsOptions.getResultBucket(),
                String.format(genomicsOptions.getAnomalyOutputDirPattern(), nameProvider.getCurrentTimeInDefaultFormat()), recognizePairedReadsWithAnomalyFn);
    }

    @Provides
    @Singleton
    public UriProvider provideUriProvider() {
        return DefaultUriProvider.withDefaultProviderRule(srcBucket);
    }
}
