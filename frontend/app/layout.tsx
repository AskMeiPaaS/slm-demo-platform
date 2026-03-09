import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'SLM Demo Platform',
  description: 'Testing Agentic AI capabilities using Qwen 3.5 Small Language Models.',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body className="bg-[#0a0a0a] text-gray-300 antialiased">
        {children}
      </body>
    </html>
  );
}