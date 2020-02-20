## GCP-PopGen Processing Pipeline

This module contains Apache Beam pipeline that processes Population Genetics datasets from [FASTQ](https://en.wikipedia.org/wiki/FASTQ_format) files to [VCF](https://en.wikipedia.org/wiki/Variant_Call_Format) files against provided [FASTA](https://en.wikipedia.org/wiki/FASTA_format) references.
Processing includes next steps:
1. Reading samples metadata from CSV files with SRA reads format
2. Downloading runs FASTQ files and FASTA references
3. Aligning FASTQ files and create SAM files
4. Converting SAM into bianry format (BAM)
5. Sorting BAM files content
6. Merging runs from the same SRA samples
7. Indexing samples BAM files
8. Variant Calling with [Deep Variant](https://github.com/google/deepvariant)
9. Exporting VCF files into big query with [gcp-variant-transforms](https://github.com/googlegenomics/gcp-variant-transforms)

### PopGen projects
You can find CSV files with gene sample metadata in the following project directories:
 - cannabis-3k
 - rice-3k
 - human-1k

### SRA gene sample metadata content example
```csv
Assay_Type	AvgSpotLen	BioSample	Center_Name	DATASTORE_provider	DATASTORE_region	Experiment	InsertSize	Instrument	LibraryLayout	LibrarySelection	LibrarySource	Library_Name	LoadDate	MBases	MBytes	Organism	Platform	ReleaseDate	Run	SRA_Sample	Sample_Name	cultivar	dev_stage	geo_loc_name	tissue	BioProject	BioSampleModel	Consent	DATASTORE_filetype	SRA_Study	age
WGS	100	SAMN00738286	UNIVERSITY OF TORONTO	gs s3 sra-sos	gs.US s3.us-east-1 sra-sos.be-md sra-sos.st-va	SRX100361	0	Illumina HiSeq 2000	SINGLE	size fractionation	GENOMIC	CS-US_SIL-1	2012-01-21	5205	3505	Cannabis sativa	ILLUMINA	2011-10-12	SRR351492	SRS266493	CS-USO31-DNA	USO-31	missing	missing	young leaf	PRJNA73819	Plant	public	sra	SRP008673	missing
WGS	100	SAMN00738286	UNIVERSITY OF TORONTO	gs s3 sra-sos	gs.US s3.us-east-1 sra-sos.be-md sra-sos.st-va	SRX100368	0	Illumina HiSeq 2000	SINGLE	size fractionation	GENOMIC	CS-US_SIL-2	2012-01-21	2306	1499	Cannabis sativa	ILLUMINA	2011-10-12	SRR351493	SRS266493	CS-USO31-DNA	USO-31	missing	missing	young leaf	PRJNA73819	Plant	public	sra	SRP008673	missing
```

### Preparing data

```
PROJECT_NAME=(specify_project_name)
SRC_BUCKET_NAME=${PROJECT_NAME}-src
WORKING_BUCKET_NAME=${PROJECT_NAME}-working
```
Following steps should be performed before you can start data processing: 
1. Create `SRC_BUCKET_NAME` GCS bucket
2. Create `WORKING_BUCKET_NAME` GCS bucket
3. Annotation CSV should be uploaded to the `SRC_BUCKET_NAME`
4. Source FASTQ files should be retrieved (e.g. from NCBI SRA archive) and uploaded to the `SRC_BUCKET_NAME`
5. Reference gene DBs (`.fasta` or `.fa`) should be retrieved (e.g. from NCBI Assemple archive)
6. Reference gene DBs should indexed. Could be used [SAM Tools utilities](http://samtools.sourceforge.net/)
7. All reference files should be uploaded to the `SRC_BUCKET_NAME`. 

As a result, `SRC_BUCKET` should have a flowing structure:  
```lang-none
+ ${SRC_BUCKET_NAME}/
    + sra_csv/
        - sra_project_1.csv
        - sra_project_2.csv
        - ...
+ fastq/
    + (sra_study_name)/
        + (sra_sample_nmae)/
            - (sra_read_name_1)_1.fastq
            - (sra_read_name_1)_2.fastq
            - (sra_read_name_2)_1.fastq
            - (sra_read_name_2)_2.fastq
            - (sra_read_name_3)_1.fastq
+ reference/
    + (reference_name)/
        - (reference_name).fa
        - (reference_name).fa.fai
```

Following files structure would be created in the `WORKING_BUCKET_NAME` GCS bucket after processing:
```lang-none
+ ${WORKING_BUCKET_NAME}/
    + processing_output/
        + (processing_date)/
            + result_aligned_bam/
                - (read_name_1)_(reference_name).bam
            + result_sorted_bam/
                - (read_name_1)_(reference_name).bam
            + result_merged_bam/
                - (sample_name_1)_(reference_name).bam
                - (sample_name_1)_(reference_name).bam.bai
            + result_dv/
                + (sample_name_1)_(reference_name)/
                    - (sample_name_1)_(reference_name).vcf
                    - (log_filed)
  + dataflow/
    + temp/
        - (dataflow_job_temp_files)
    + staging/
        - (dataflow_job_staging_files)
```
### Running example

#### Per-sample (fastq => bigquery) processing job:
```bash
mvn clean package
java -cp target/gcp-popgen-0.0.2.jar \
    com.google.allenday.popgen.NanostreamBatchApp \
        --project=cannabis-3k \
        --region="us-central1" \
        --workerMachineType=n1-standard-4 \
        --maxNumWorkers=20 \
        --numWorkers=5 \
        --runner=DataflowRunner \
        --srcBucket=cannabis-3k \
        --stagingLocation="gs://dataflow-temp-and-staging/staging/"\
        --tempLocation="gs://dataflow-temp-and-staging/temp/"\
        --inputCsvUri="gs://cannabis-3k/sra_csv_index/converted/*" \
        --sraSamplesToFilter="SRS1098403" \
        --outputGcsUri="gs://cs10-run1-results/processing_output/" \
        --referenceNamesList="AGQN03","MNPR01" \
        --allReferencesDirGcsUri="gs://cannabis-3k/reference/" \
        --memoryOutputLimit=0 \
        --controlPipelineWorkerRegion="us-west2" \
        --stepsWorkerRegion="us-central1" \
        --makeExamplesWorkers=4 \
        --makeExamplesCoresPerWorker=16 \
        --makeExamplesRamPerWorker=60 \
        --makeExamplesDiskPerWorker=90 \
        --callVariantsCoresPerWorker=16 \
        --callVariantsRamPerWorker=60 \
        --callVariantsDiskPerWorker=60 \
        --deepVariantShards=4 \
        --exportVcfToBq=True \
        --vcfBqDatasetAndTablePattern="cannabis_3k_results_test.GENOMICS_VARIATIONS_%s"
```

#### Per-sample (fastq => vcf) processing job:
```
# Simply set  paramater `exportVcfToBq=False` of the fastq=>bigquery pipeline:
--exportVcfToBq=False
```


## Docs in general terms

### Running
Use following commands to run processing:

```
mvn clean package
java -cp target/gcp-popgen-(current_version).jar \
    com.google.allenday.popgen.NanostreamBatchApp \
        --project=(gcp_project_id) \
        --region=(gcp_worker_machines_region) \
        --workerMachineType=(gcp_worker_machines_type) \
        --maxNumWorkers=(max_number_of_workers) \
        --numWorkers=(start_number_of_workers) \
        --runner=(apache_beam_runner) \
        --stagingLocation=(dataflow_gcs_staging_location) \
        --tempLocation=(dataflow_gcs_temp_location) \
        --srcBucket=(all_source_data_gcs_bucket) \
        --inputCsvUri=(gcs_uri_of_sra_csv_file) \ # Could be pattern with wildcards (*) 
        --outputGcsUri=(gcs_uri_for_writing_results) \
        --referenceNamesList=(list_of_references_names_that will_be_used) \
        --allReferencesDirGcsUri=(gcs_uri_of_dir_with_references) \
```
If VCF results should be exported to BigQuery you need to add following arguments:
```
        --exportVcfToBq=True \
        --vcfBqDatasetAndTablePattern=(bq_dataset_and_table_name_pattern)
```

Also could be added optional parameters:
```
        --sraSamplesToFilter=(list_of_sra_samles_to_process) \ # Use if need to process some certain samples
        --memoryOutputLimit=(max_file_size_to_pass_internaly_between_transforms) \ # Set 0 to store all intermediante files in GCS \
```

Optional [DeepVariant](https://github.com/google/deepvariant) parameters:

```
        --controlPipelineWorkerRegion=(region_of_deep_varint_control_pipeline) \
        --stepsWorkerRegion=(region_of_deep_varint_steps_machines) \
        --makeExamplesWorkers=(number) \
        --makeExamplesCoresPerWorker=(number) \
        --makeExamplesRamPerWorker=(number) \
        --makeExamplesDiskPerWorker=(number) \
        --call_variants_workers=(number)
        --callVariantsCoresPerWorker=(number) \
        --callVariantsRamPerWorker=(number) \
        --callVariantsDiskPerWorker=(number) \
        --postprocess_variants_cores=(number) \
        --postprocess_variants_ram_gb=(number) \
        --postprocess_variants_disk_gb=(number) \
        --preemptible=(bool) \
        --max_premptible_tries=(number) \
        --max_non_premptible_tries=(number) \
        --deepVariantShards=(number) \
```
```
 


