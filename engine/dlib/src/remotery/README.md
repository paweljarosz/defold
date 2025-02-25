
## Update the library

See [](./update.sh) for an copying the files

Copy the C file to engine:

    cp -v ~/notwork/Remotery/lib/*.c engine/dlib/src/remotery/

Copy the header file to engine:

    cp -v ~/notwork/Remotery/lib/*.h engine/dlib/src/dmsdk/external/remotery/Remotery.h


Copy the HTML files to the editor:

    cp -v -r ~/notwork/Remotery/vis/ editor/resources/engine-profiler/remotery/vis

## Manually update the code

Looking at the current code, we do a Defold fixup to modify the property name strings.
We do this in order to be able to have a prefix (`rmtp_`), but also to avoid displaying this prefix
when presented in the engine and in the HTML profile page.

#### Create the patch

You can update the patch by making your changes to a "vanilla" version of the Remotery source code

    git diff engine/dlib/src/remotery/Remotery.* > engine/dlib/src/remotery/defold.patch

Then, you can apply these changes

    git apply ./engine/dlib/src/remotery/defold.patch

## Test the web page

You can start the server locally:

    (cd editor/resources/engine-profiler/remotery/vis && python -m SimpleHTTPServer)

