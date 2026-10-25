## Module graph changes

```mermaid
flowchart LR
  m_dev_example_a["dev.example:a"]
  m_dev_example_b["dev.example:b"]
  m_dev_example_c["dev.example:c"]
  m_dev_example_a -->|"compile"| m_dev_example_b
  m_dev_example_a -->|"compile"| m_dev_example_c
  linkStyle 0 stroke:#ef4444,stroke-dasharray:5 5
  classDef changed fill:#fef9c3,stroke:#eab308,color:#713f12
  class m_dev_example_a changed
  classDef context fill:#f3f4f6,stroke:#9ca3af,color:#6b7280
  class m_dev_example_b context
  class m_dev_example_c context
```

| Module | Change |
| --- | --- |
| `dev.example:a` | edge removed `dev.example:b` (compile); edge added `dev.example:c` (compile); dependency `org.example:lib` `1.0.0` -> `2.0.0` |
