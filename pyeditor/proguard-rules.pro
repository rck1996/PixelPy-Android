# These four methods are called dynamically from Python through Chaquopy.
# The class itself may still be renamed by R8: Python receives the object instance.
-keepclassmembers,allowoptimization class com.pixelpy.editor.InputBridge {
    public java.lang.String request(java.lang.String);
    public void submit(java.lang.String);
    public void cancel();
    public boolean isCancelled();
}
