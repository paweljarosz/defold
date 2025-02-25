
class TraceDrop
{
    constructor(remotery)
    {
        this.Remotery = remotery;

        // Create a full-page overlay div for dropping files onto
        this.DropNode = DOM.Node.CreateHTML("<div id='DropZone' class='DropZone'>Load Remotery Trace</div>");
        document.body.appendChild(this.DropNode);

        // Attach drop handlers
        window.addEventListener("dragenter", () => this.ShowDropZone());
        this.DropNode.addEventListener("dragenter", (e) => this.AllowDrag(e));
        this.DropNode.addEventListener("dragover", (e) => this.AllowDrag(e));
        this.DropNode.addEventListener("dragleave", () => this.HideDropZone());
        this.DropNode.addEventListener("drop", (e) => this.OnDrop(e));
    }
    
    ShowDropZone()
    {
        this.DropNode.style.display = "flex";
    }

    HideDropZone()
    {
        this.DropNode.style.display = "none";
    }

    AllowDrag(evt)
    {
        // Prevent the default drag handler kicking in
        evt.preventDefault();

        evt.dataTransfer.dropEffect = "copy";
    }

    OnDrop(evt)
    {
        // Prevent the default drop handler kicking in
        evt.preventDefault();

        this.HideDropZone(evt);

        // Get the file that was dropped
        let files = DOM.Event.GetDropFiles(evt);
        if (files.length == 0)
        {
            alert("No files dropped");
            return;
        }
        if (files.length > 1)
        {
            alert("Too many files dropped");
            return;
        }

        // Check file type
        let file = files[0];
        if (!file.name.endsWith(".rbin"))
        {
            alert("Not the correct .rbin file type");
            return;
        }

        // Background-load the file
        var remotery = this.Remotery;
        let file_reader = new FileReader();
        file_reader.onload = function()
        {
            // Create the data reader and verify the header
            let data_view = new DataView(this.result);
            let data_view_reader = new DataViewReader(data_view, 0);
            let header = data_view_reader.GetStringOfLength(8);
            if (header != "RMTBLOGF")
            {
                alert("Not a valid Remotery Log File");
                return;
            }

            remotery.Clear();
            
            try
            {
                // Forward all recorded events to message handlers
                while (!data_view_reader.AtEnd())
                {
                    remotery.Server.CallMessageHandlers(data_view_reader);
                }
            }
            catch (e)
            {
                // The last message may be partially written due to process exit
                // Catch this safely as it's a valid state for the file to be in
                if (e instanceof RangeError)
                {
                    console.log("Aborted reading last message");
                }
            }

            // After loading completes, populate the UI which wasn't updated during loading

            remotery.Console.TriggerUpdate();

            // Set frame history for each timeline thread
            for (let name in remotery.FrameHistory)
            {
                let frame_history = remotery.FrameHistory[name];
                remotery.SampleTimelineWindow.OnSamples(name, frame_history);
            }
            remotery.SampleTimelineWindow.DrawAllRows();

            for (let name in remotery.ProcessorFrameHistory)
            {
                let frame_history = remotery.ProcessorFrameHistory[name];
                remotery.ProcessorTimelineWindow.OnSamples(name, frame_history);
            }
            remotery.ProcessorTimelineWindow.DrawAllRows();
            
            // Set the last frame of each thread sample history on the sample windows
            for (let name in remotery.gridWindows)
            {
                let sample_window = remotery.gridWindows[name];
                let frame_history = remotery.FrameHistory[name];
                let frame = frame_history[frame_history.length - 1];
                sample_window.UpdateEntries(frame.NbSamples, frame.SampleDigest, frame.Samples);
            }

            // Pause for viewing
            remotery.TitleWindow.Pause();
        };
        file_reader.readAsArrayBuffer(file);
    }
}