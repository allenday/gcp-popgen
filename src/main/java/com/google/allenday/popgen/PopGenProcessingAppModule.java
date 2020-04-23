package com.google.allenday.popgen;

import com.google.allenday.genomics.core.batch.BatchProcessingModule;
import com.google.allenday.genomics.core.batch.BatchProcessingPipelineOptions;
import com.google.allenday.genomics.core.batch.PreparingTransform;
import com.google.allenday.genomics.core.io.BaseUriProvider;
import com.google.allenday.genomics.core.io.DefaultBaseUriProvider;
import com.google.allenday.genomics.core.io.FileUtils;
import com.google.allenday.genomics.core.pipeline.GenomicsProcessingParams;
import com.google.allenday.genomics.core.utils.NameProvider;
import com.google.allenday.popgen.anomaly.DetectAnomalyTransform;
import com.google.allenday.popgen.anomaly.RecognizePairedReadsWithAnomalyFn;
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
                                     GenomicsProcessingParams genomicsOptions,
                                     Integer maxFastqSizeMB,
                                     Integer maxFastqChunkSize,
                                     Integer bamRegionSize,
                                     boolean withFinalMerge) {
        super(srcBucket, inputCsvUri, sraSamplesToFilter, sraSamplesToSkip, project, region,
                genomicsOptions, maxFastqSizeMB, maxFastqChunkSize, bamRegionSize,
                withFinalMerge);
    }

    public PopGenProcessingAppModule(BatchProcessingPipelineOptions batchProcessingPipelineOptions) {
        this(
                batchProcessingPipelineOptions.getSrcBucket(),
                batchProcessingPipelineOptions.getInputCsvUri(),
                batchProcessingPipelineOptions.getSraSamplesToFilter(),
                batchProcessingPipelineOptions.getSraSamplesToSkip(),
                batchProcessingPipelineOptions.getProject(),
                batchProcessingPipelineOptions.getRegion(),
                GenomicsProcessingParams.fromAlignerPipelineOptions(batchProcessingPipelineOptions),
                batchProcessingPipelineOptions.getMaxFastqSizeMB(),
                batchProcessingPipelineOptions.getMaxFastqChunkSize(),
                batchProcessingPipelineOptions.getBamRegionSize(),
                batchProcessingPipelineOptions.getWithFinalMerge()
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
        return new DetectAnomalyTransform("Filter anomaly and prepare for processing", genomicsParams.getResultBucket(),
                String.format(genomicsParams.getAnomalyOutputDirPattern(), nameProvider.getCurrentTimeInDefaultFormat()),
                recognizePairedReadsWithAnomalyFn);
    }

    @Provides
    @Singleton
    public BaseUriProvider provideUriProvider() {
        return DefaultBaseUriProvider.withDefaultProviderRule(srcBucket);
    }
}
