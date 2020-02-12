package com.google.allenday.popgen.post_processing;

import com.google.allenday.genomics.core.batch.BatchProcessingPipelineOptions;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.Validation;

public interface FinalizePopGenProcessingOptions extends BatchProcessingPipelineOptions {

    @Validation.Required
    PopGenPostProcessingApp.PostProcessingType getPostProcessingType();

    void setPostProcessingType(PopGenPostProcessingApp.PostProcessingType value);

    @Description("Name of the directory with staged data")
    @Validation.Required
    String getStagedSubdir();

    void setStagedSubdir(String value);

    @Description("Lower file size threshold for Deep Variant processing")
    @Default.Integer(0)
    Integer getMinDvFileSizeThreshold();

    void setMinDvFileSizeThreshold(Integer value);

    @Description("Upper file size threshold for Deep Variant processing")
    @Default.Integer(Integer.MAX_VALUE)
    Integer getMaxDvFileSizeThreshold();

    void setMaxDvFileSizeThreshold(Integer value);
}
