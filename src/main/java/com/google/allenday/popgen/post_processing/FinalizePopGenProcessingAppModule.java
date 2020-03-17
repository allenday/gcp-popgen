package com.google.allenday.popgen.post_processing;

import com.google.allenday.genomics.core.io.FileUtils;
import com.google.allenday.genomics.core.io.IoUtils;
import com.google.allenday.genomics.core.parts_processing.*;
import com.google.allenday.genomics.core.processing.vcf_to_bq.VcfToBqFn;
import com.google.allenday.genomics.core.reference.ReferenceProvider;
import com.google.allenday.genomics.core.utils.NameProvider;
import com.google.allenday.popgen.PopGenProcessingAppModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;


public class FinalizePopGenProcessingAppModule extends PopGenProcessingAppModule {

    private String stagedDir;
    private Integer minDvFileSizeThreshold;
    private Integer maxDvFileSizeThreshold;

    public FinalizePopGenProcessingAppModule(FinalizePopGenProcessingOptions options) {
        super(options);
        this.stagedDir = options.getStagedSubdir();
        this.minDvFileSizeThreshold = options.getMinDvFileSizeThreshold();
        this.maxDvFileSizeThreshold = options.getMaxDvFileSizeThreshold();
    }


    @Provides
    @Singleton
    public StagingPathsBulder provideStagingPathsBulder() {
        return StagingPathsBulder.init(genomicsOptions.getResultBucket(),
                genomicsOptions.getBaseOutputDir() + stagedDir);
    }

    @Provides
    @Singleton
    public VcfToBqBatchTransform provideVcfToBqBatchTransform(
            VcfToBqBatchTransform.PrepareVcfToBqBatchFn prepareVcfToBqBatchFn,
            VcfToBqBatchTransform.SaveVcfToBqResults saveVcfToBqResults,
            VcfToBqFn vcfToBqFn
    ) {
        return new VcfToBqBatchTransform(prepareVcfToBqBatchFn, saveVcfToBqResults, vcfToBqFn);
    }


    @Provides
    @Singleton
    public VcfToBqBatchTransform.PrepareVcfToBqBatchFn providePrepareVcfToBqBatchFn(FileUtils fileUtils, IoUtils ioUtils,
                                                                                    StagingPathsBulder stagingPathsBulder,
                                                                                    NameProvider nameProvider) {
        return new VcfToBqBatchTransform.PrepareVcfToBqBatchFn(fileUtils, ioUtils, stagingPathsBulder,
                nameProvider.getCurrentTimeInDefaultFormat());
    }


    @Provides
    @Singleton
    public VcfToBqBatchTransform.SaveVcfToBqResults provideSaveVcfToBqResults(IoUtils ioUtils,
                                                                              StagingPathsBulder stagingPathsBulder) {
        return new VcfToBqBatchTransform.SaveVcfToBqResults(stagingPathsBulder, ioUtils);
    }

    @Provides
    @Singleton
    public PrepareAlignNotProcessedFn providePrepareAlignNotProcessedFn(FileUtils fileUtils, StagingPathsBulder stagingPathsBulder) {
        return new PrepareAlignNotProcessedFn(fileUtils, genomicsOptions.getGeneReferences(), stagingPathsBulder,
                genomicsOptions.getAllReferencesDirGcsUri());
    }

    @Provides
    @Singleton
    public PrepareSortNotProcessedFn providePrepareSortNotProcessedFn(FileUtils fileUtils, StagingPathsBulder stagingPathsBulder) {
        return new PrepareSortNotProcessedFn(fileUtils, genomicsOptions.getGeneReferences(), stagingPathsBulder,
                genomicsOptions.getAllReferencesDirGcsUri());
    }

    @Provides
    @Singleton
    public PrepareMergeNotProcessedFn providePrepareMergeNotProcessedFn(FileUtils fileUtils, StagingPathsBulder stagingPathsBulder) {
        return new PrepareMergeNotProcessedFn(fileUtils, genomicsOptions.getGeneReferences(), stagingPathsBulder,
                genomicsOptions.getAllReferencesDirGcsUri());
    }

    @Provides
    @Singleton
    public PrepareIndexNotProcessedFn providePrepareIndexNotProcessedFn(FileUtils fileUtils, StagingPathsBulder stagingPathsBulder) {
        return new PrepareIndexNotProcessedFn(fileUtils, genomicsOptions.getGeneReferences(), stagingPathsBulder,
                genomicsOptions.getAllReferencesDirGcsUri());
    }

    @Provides
    @Singleton
    public PrepareDvNotProcessedFn providePrepareIndexNotProcessedFn(ReferenceProvider referencesProvider,
                                                                     FileUtils fileUtils, StagingPathsBulder stagingPathsBulder) {
        return new PrepareDvNotProcessedFn(genomicsOptions.getGeneReferences(), minDvFileSizeThreshold,
                maxDvFileSizeThreshold, stagingPathsBulder, genomicsOptions.getAllReferencesDirGcsUri());
    }

    @Provides
    @Singleton
    public CheckExistenceFn provideCheckExistenceFn(FileUtils fileUtils, IoUtils ioUtils, StagingPathsBulder stagingPathsBulder) {
        return new CheckExistenceFn(fileUtils, ioUtils, genomicsOptions.getGeneReferences(), stagingPathsBulder);
    }
}
