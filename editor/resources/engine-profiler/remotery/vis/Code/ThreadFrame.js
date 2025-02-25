

ThreadFrame = (function()
{
	function ThreadFrame(message)
	{
		// Persist the required message data
		this.NbSamples = message.nb_samples;
		this.SampleDigest = message.sample_digest;
		this.Samples = message.samples;
		this.PartialTree = message.partial_tree > 0 ? true : false;

		// Calculate the frame start/end times
		this.StartTime_us = 0;
		this.EndTime_us = 0;
		var nb_root_samples = this.Samples.length;
		if (nb_root_samples > 0)
		{
			var last_sample = this.Samples[nb_root_samples - 1];
			this.StartTime_us = this.Samples[0].us_start;
			this.EndTime_us = last_sample.us_start + last_sample.us_length;
		}

		this.Length_us = this.EndTime_us - this.StartTime_us;
	}


	return ThreadFrame;
})();

PropertySnapshotFrame = (function()
{
	function PropertySnapshotFrame(message)
	{
		this.nbSnapshots = message.nbSnapshots;
		this.snapshots = message.snapshots;
	}

	return PropertySnapshotFrame;
})();